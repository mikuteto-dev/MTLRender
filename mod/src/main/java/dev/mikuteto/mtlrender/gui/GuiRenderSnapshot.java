package dev.mikuteto.mtlrender.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.client.renderer.state.gui.TiledBlitRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBannerResultRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiBookModelRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiProfilerChartRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fc;
import dev.mikuteto.mtlrender.mixin.GuiRenderStateAccess;
import dev.mikuteto.mtlrender.mixin.GuiRenderStateNodeAccess;

public final class GuiRenderSnapshot {
	private static final AtomicLong PUBLISHED = new AtomicLong();
	private static final AtomicLong RESOURCE_GENERATION = new AtomicLong();
	private static final AtomicLong LIFECYCLE_GENERATION = new AtomicLong();
	private static final AtomicLong FRAME = new AtomicLong();
	private static volatile GuiFrameCacheKey current;
	private static volatile boolean lastHit;
	private static volatile boolean frameActive;
	private final List<Node> strata;
	private final int firstStratumAfterBlur;
	private final Set<Long> itemModelIdentityTokens;
	private final Float panoramaSpin;
	private final Color clearColor;
	private final boolean hudHidden;
	private final boolean requiresFreshResources;

	private GuiRenderSnapshot(
		final List<Node> strata,
		final int firstStratumAfterBlur,
		final Set<Long> itemModelIdentityTokens,
		final Float panoramaSpin,
		final Color clearColor,
		final boolean hudHidden,
		final boolean requiresFreshResources
	) {
		this.strata = List.copyOf(strata);
		this.firstStratumAfterBlur = firstStratumAfterBlur;
		this.itemModelIdentityTokens = Set.copyOf(itemModelIdentityTokens);
		this.panoramaSpin = panoramaSpin;
		this.clearColor = clearColor;
		this.hudHidden = hudHidden;
		this.requiresFreshResources = requiresFreshResources;
	}

	public static GuiRenderSnapshot empty() {
		return new GuiRenderSnapshot(List.of(new Node(List.of(), null)), Integer.MAX_VALUE, Set.of(), null, new Color(0.0F, 0.0F, 0.0F, 0.0F), false, false);
	}

	public static GuiRenderSnapshot capture(final GuiRenderState source) {
		Objects.requireNonNull(source, "source");
		GuiRenderStateAccess state = (GuiRenderStateAccess)source;
		List<Node> strata = new ArrayList<>();
		for (Object stratum : state.mtlrender$strata()) {
			strata.add(captureNode(stratum));
		}
		Set<Long> identities = new LinkedHashSet<>();
		for (Object identity : state.mtlrender$itemModelIdentities()) {
			identities.add(token(identity));
		}
		var panorama = state.mtlrender$panoramaRenderState();
		var clear = state.mtlrender$clearColorOverride();
		boolean requiresFreshResources = strata.stream().anyMatch(GuiRenderSnapshot::requiresFreshResources);
		return new GuiRenderSnapshot(
			strata,
			state.mtlrender$firstStratumAfterBlur(),
			identities,
			panorama == null ? null : panorama.spin(),
			new Color(clear.x(), clear.y(), clear.z(), clear.w()),
			state.mtlrender$isHudHidden(),
			requiresFreshResources
		);
	}

	private static Node captureNode(final Object source) {
		GuiRenderStateNodeAccess node = (GuiRenderStateNodeAccess)source;
		List<Entry> entries = new ArrayList<>();
		append(entries, "element", node.mtlrender$elementStates());
		append(entries, "glyph", node.mtlrender$glyphStates());
		append(entries, "item", node.mtlrender$itemStates());
		append(entries, "text", node.mtlrender$textStates());
		append(entries, "pip", node.mtlrender$picturesInPictureStates());
		Node child = node.mtlrender$up() == null ? null : captureNode(node.mtlrender$up());
		return new Node(entries, child);
	}

	private static void append(final List<Entry> output, final String category, final List<?> states) {
		if (states == null) {
			return;
		}
		for (Object value : states) {
			output.add(describe(category, value));
		}
	}

	private static boolean requiresFreshResources(final Node node) {
		return node.entries().stream().anyMatch(Entry::requiresFreshResources)
			|| (node.child() != null && requiresFreshResources(node.child()));
	}

	private static Entry describe(final String category, final Object value) {
		ScreenRectangle bounds = value instanceof net.minecraft.client.renderer.state.gui.ScreenArea area ? area.bounds() : null;
		ScreenRectangle scissor = null;
		boolean requiresFreshResources = value instanceof GuiItemRenderState || value instanceof GuiTextRenderState
			|| value instanceof PictureInPictureRenderState || value instanceof GlyphRenderState;
		List<Long> resources = new ArrayList<>();
		List<Integer> ints = new ArrayList<>();
		List<Float> floats = new ArrayList<>();
		List<Float> pose = List.of();
		if (value instanceof GuiElementRenderState element) {
			scissor = element.scissorArea();
			resources.add(token(element.pipeline()));
			resources.add(token(element.textureSetup()));
			if (element instanceof BlitRenderState state) {
				pose = matrix(state.pose());
				ints = List.of(state.x0(), state.y0(), state.x1(), state.y1(), state.color());
				floats = List.of(state.u0(), state.u1(), state.v0(), state.v1());
			} else if (element instanceof TiledBlitRenderState state) {
				pose = matrix(state.pose());
				ints = List.of(state.tileWidth(), state.tileHeight(), state.x0(), state.y0(), state.x1(), state.y1(), state.color());
				floats = List.of(state.u0(), state.u1(), state.v0(), state.v1());
			} else if (element instanceof ColoredRectangleRenderState state) {
				pose = matrix(state.pose());
				ints = List.of(state.x0(), state.y0(), state.x1(), state.y1(), state.col1(), state.col2());
			} else if (element instanceof GlyphRenderState state) {
				pose = matrix(state.pose());
				resources.add(token(state.renderable()));
			}
		} else if (value instanceof GuiItemRenderState state) {
			scissor = state.scissorArea();
			pose = matrix(state.pose());
			ints = List.of(state.x(), state.y());
			GuiItemCacheKey key = GuiItemCacheKey.from(state, resourceGeneration(), frameGeneration());
			resources.add((long)key.hashCode());
			resources.add(key.animated() ? key.animationVersion() : 0L);
			resources.add(key.resourceGeneration());
			ScreenRectangle oversized = state.oversizedItemBounds();
			if (oversized != null) {
				ints = appendRectangle(ints, oversized);
			}
		} else if (value instanceof GuiTextRenderState state) {
			scissor = state.scissor;
			pose = matrix(state.pose);
			state.ensurePrepared();
			resources.add(token(state));
		} else if (value instanceof PictureInPictureRenderState state) {
			scissor = state.scissorArea();
			ints = List.of(state.x0(), state.y0(), state.x1(), state.y1());
			floats = List.of(state.scale());
			pose = matrix(state.pose());
			resources.add(token(state.getClass()));
			if (state instanceof GuiBannerResultRenderState banner) {
				resources.add(token(banner.flag()));
				resources.add(token(banner.baseColor()));
				resources.add(token(banner.resultBannerPatterns()));
			} else if (state instanceof GuiBookModelRenderState book) {
				resources.add(token(book.bookModel()));
				resources.add(token(book.texture()));
				floats = List.of(book.scale(), book.open(), book.flip());
			} else if (state instanceof GuiEntityRenderState entity) {
				resources.add(token(entity.renderState()));
				resources.add(token(entity.translation()));
				resources.add(token(entity.rotation()));
				resources.add(token(entity.overrideCameraAngle()));
			} else if (state instanceof GuiSkinRenderState skin) {
				resources.add(token(skin.playerModel()));
				resources.add(token(skin.texture()));
				floats = List.of(skin.scale(), skin.rotationX(), skin.rotationY(), skin.pivotY());
			} else if (state instanceof GuiProfilerChartRenderState chart) {
				resources.add(token(chart.chartData()));
			} else if (state instanceof OversizedItemRenderState item) {
				resources.add(token(item.guiItemRenderState()));
			}
		}
		return new Entry(category, value.getClass().getName(), rectangle(bounds), rectangle(scissor), pose, ints, floats, resources, requiresFreshResources);
	}

	private static List<Integer> appendRectangle(final List<Integer> values, final ScreenRectangle rectangle) {
		List<Integer> result = new ArrayList<>(values);
		result.add(rectangle.left());
		result.add(rectangle.top());
		result.add(rectangle.width());
		result.add(rectangle.height());
		return List.copyOf(result);
	}

	private static List<Integer> rectangle(final ScreenRectangle rectangle) {
		return rectangle == null ? List.of() : List.of(rectangle.left(), rectangle.top(), rectangle.width(), rectangle.height());
	}

	private static List<Float> matrix(final Matrix3x2fc matrix) {
		return List.of(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m20(), matrix.m21());
	}

	private static long token(final Object value) {
		return value == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(value));
	}

	public static GuiFrameCacheKey capture(final GuiRenderState state, final int mouseX, final int mouseY, final int windowWidth, final int windowHeight, final int guiScale, final int screenIdentity, final int overlayIdentity, final long resourceIdentity, final int worldIdentity) {
		Fingerprinter fingerprinter = new Fingerprinter();
		fingerprinter.value(state);
		fingerprinter.value(state.panoramaRenderState);
		fingerprinter.value(state.clearColorOverride);
		fingerprinter.value(state.isHudHidden);
		state.forEachElement(element -> fingerprinter.value(element), GuiRenderState.TraverseRange.ALL);
		state.forEachItem(item -> fingerprinter.item(item));
		state.forEachText(text -> fingerprinter.value(text));
		state.forEachPictureInPicture(picture -> fingerprinter.value(picture));
		long context = 17L;
		context = mix(context, mouseX);
		context = mix(context, mouseY);
		context = mix(context, windowWidth);
		context = mix(context, windowHeight);
		context = mix(context, guiScale);
		context = mix(context, screenIdentity);
		context = mix(context, overlayIdentity);
		context = mix(context, resourceIdentity);
		context = mix(context, worldIdentity);
		return GuiFrameCacheKey.staticKey(fingerprinter.hash, context);
	}

	public static long resourceGeneration() {
		return RESOURCE_GENERATION.get();
	}

	public static long frameGeneration() {
		return FRAME.get();
	}

	public static long lifecycleGeneration() {
		return LIFECYCLE_GENERATION.get();
	}

	public static void beginFrame() {
		FRAME.incrementAndGet();
		frameActive = true;
	}

	public static void endFrame() {
		frameActive = false;
	}

	public static boolean frameActive() {
		return frameActive;
	}

	public static void invalidate() {
		LIFECYCLE_GENERATION.incrementAndGet();
		current = null;
		lastHit = false;
	}

	public static void resourceReloadStarted() {
		RESOURCE_GENERATION.incrementAndGet();
		GuiMapAtlas.invalidate();
		GlyphLayoutCache.clear();
		TooltipLayoutCache.clear();
		invalidate();
	}

	public static void resourcesApplied() {
		RESOURCE_GENERATION.incrementAndGet();
		GuiMapAtlas.invalidate();
		GlyphLayoutCache.clear();
		TooltipLayoutCache.clear();
		invalidate();
	}

	public static void publish(final GuiFrameCacheKey key, final boolean useCache) {
		GuiFrameCacheKey previous = current;
		lastHit = useCache && previous != null && previous.equals(key);
		current = key;
		PUBLISHED.incrementAndGet();
	}

	public static void publish(final GuiFrameCacheKey key) { publish(key, true); }
	public static GuiFrameCacheKey current() { return current; }
	public static long extractionCount() { return PUBLISHED.get(); }
	public static long frameCount() { return FRAME.get(); }
	public static boolean lastHit() { return lastHit; }

	private static long mix(final long value, final long input) {
		return (value ^ input) * 0x9E3779B185EBCA87L;
	}

	private static final class Fingerprinter {
		private final Map<Object, Boolean> seen = new IdentityHashMap<>();
		private long hash = 0xCBF29CE484222325L;

		private void item(final GuiItemRenderState item) {
			GuiItemCacheKey key = GuiItemCacheKey.from(item, resourceGeneration(), frameGeneration());
			for (GuiItemCacheKey.ModelIdentityElement element : key.modelIdentity()) {
				text(element.type().getName());
				text(element.valueStable() ? String.valueOf(element.value()) : Integer.toString(element.value().hashCode()));
			}
			value(key.animated());
			value(key.animationVersion());
			value(key.resourceGeneration());
		}

		private void value(final Object value) {
			if (value == null) { hash = mix(hash, 0L); return; }
			if (value instanceof Number number) { text(number.toString()); return; }
			if (value instanceof Boolean bool) { hash = mix(hash, bool ? 1L : 2L); return; }
			if (value instanceof CharSequence sequence) { text(sequence.toString()); return; }
			if (value.getClass().isEnum()) { text(value.getClass().getName()); text(((Enum<?>)value).name()); return; }
			if (seen.put(value, Boolean.TRUE) != null) { hash = mix(hash, 31L); return; }
			text(value.getClass().getName());
			if (value.getClass().isRecord()) {
				for (RecordComponent component : value.getClass().getRecordComponents()) {
					try { value(component.getAccessor().invoke(value)); } catch (ReflectiveOperationException exception) { text(component.getName()); }
				}
				return;
			}
			for (Field field : value.getClass().getDeclaredFields()) {
				if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) { continue; }
				try { if (field.trySetAccessible()) { value(field.get(value)); } } catch (IllegalAccessException exception) { text(field.getName()); }
			}
		}

		private void text(final String value) { hash = mix(hash, value.hashCode()); }
		private static long mix(final long value, final long input) { return (value ^ input) * 0x100000001B3L; }
	}

	public List<Node> strata() { return strata; }
	public int firstStratumAfterBlur() { return firstStratumAfterBlur; }
	public Set<Long> itemModelIdentityTokens() { return itemModelIdentityTokens; }
	public Float panoramaSpin() { return panoramaSpin; }
	public Color clearColor() { return clearColor; }
	public boolean hudHidden() { return hudHidden; }
	public boolean requiresFreshResources() { return requiresFreshResources; }
	public boolean reusable() { return !requiresFreshResources; }
	public record Color(float red, float green, float blue, float alpha) { }
	public record Node(List<Entry> entries, Node child) { public Node { entries = List.copyOf(entries); } }
	public record Entry(String category, String type, List<Integer> bounds, List<Integer> scissor, List<Float> pose, List<Integer> ints, List<Float> floats, List<Long> resources, boolean requiresFreshResources) {
		public Entry { bounds = List.copyOf(bounds); scissor = List.copyOf(scissor); pose = List.copyOf(pose); ints = List.copyOf(ints); floats = List.copyOf(floats); resources = List.copyOf(resources); }
	}
}
