package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.shadow.ShadowTargetPlan;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PackTargetSet implements AutoCloseable {
	private final GpuDevice device;
	private final Map<String, Pair> colors;
	private final Map<String, Target> depths;
	private final Map<String, Target> shadows;
	private final int width;
	private final int height;
	private final long planVersion;
	private final RenderTargetPlan plan;
	private final Optional<ShadowTargetPlan> shadowPlan;
	private boolean closed;

	public PackTargetSet(GpuDevice device, RenderTargetPlan plan, int width, int height) {
		this(device, 0L, plan, Optional.empty(), width, height);
	}

	public PackTargetSet(GpuDevice device, long planVersion, RenderTargetPlan plan, int width, int height) {
		this(device, planVersion, plan, Optional.empty(), width, height);
	}

	public PackTargetSet(GpuDevice device, long planVersion, RenderTargetPlan plan, Optional<ShadowTargetPlan> shadowPlan,
		int width, int height) {
		this.device = Objects.requireNonNull(device, "device");
		this.plan = Objects.requireNonNull(plan, "plan");
		this.shadowPlan = Objects.requireNonNull(shadowPlan, "shadow plan");
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("dimensions must be positive");
		this.width = width;
		this.height = height;
		this.planVersion = planVersion;
		this.colors = new LinkedHashMap<>();
		this.depths = new LinkedHashMap<>();
		this.shadows = new LinkedHashMap<>();
		allocate();
	}

	public static Optional<PackTargetSet> allocate(GpuDevice device, Optional<RenderTargetPlan> plan, long version,
		int width, int height) {
		return plan.map(value -> new PackTargetSet(device, version, value, width, height));
	}

	public static Optional<PackTargetSet> allocate(GpuDevice device, Optional<RenderTargetPlan> plan,
		Optional<ShadowTargetPlan> shadowPlan, long version, int width, int height) {
		return plan.map(value -> new PackTargetSet(device, version, value, shadowPlan, width, height));
	}

	public int width() { return width; }
	public int height() { return height; }
	public long planVersion() { return planVersion; }
	public RenderTargetPlan plan() { return plan; }

	public GpuTextureView main() { return colorPair("colortex0").viewMain(); }
	public GpuTextureView alt() { return colorPair("colortex0").viewAlt(); }
	public GpuTextureView colortex(int index) { return colorPair("colortex" + index).viewMain(); }
	public GpuTextureView colortexAlt(int index) { return colorPair("colortex" + index).viewAlt(); }
	public Optional<GpuTextureView> optionalColor(int index) {
		Pair pair = colors.get("colortex" + index);
		return pair == null ? Optional.empty() : Optional.of(pair.viewMain());
	}
	public GpuTextureView depthtex(int index) { return required(depths.get("depthtex" + index), "depthtex" + index).view(); }
	public Optional<GpuTextureView> optionalDhDepth(int index) {
		if (index < 0 || index > 1) throw new IllegalArgumentException("invalid DH depth index: " + index);
		Target target = depths.get("depthtex" + index);
		return target == null ? Optional.empty() : Optional.of(target.view());
	}
	public Optional<GpuTextureView> optionalShadow(String name) {
		Target target = shadows.get(name);
		return target == null ? Optional.empty() : Optional.of(target.view());
	}
	public Optional<GpuTextureView> shadowtex(int index) { return optionalShadow("shadowtex" + index); }
	public Optional<GpuTextureView> shadowcolor(int index) { return optionalShadow("shadowcolor" + index); }
	public Map<String, GpuTextureView> colorViews() { return views(colors); }
	public ViewSnapshot snapshot() {
		Map<String, ViewPair> snapshot = new LinkedHashMap<>();
		colors.forEach((name, pair) -> {
			View main = new View(name, pair.main().texture(), pair.main().view());
			View alternate = new View(name, pair.alt().texture(), pair.alt().view());
			snapshot.put(name, new ViewPair(main, alternate));
		});
		return new ViewSnapshot(planVersion, plan, width, height, snapshot);
	}
	public Map<String, GpuTextureView> depthViews() { return singleViews(depths); }
	public Map<String, GpuTextureView> shadowViews() { return singleViews(shadows); }

	public boolean same(long version, RenderTargetPlan nextPlan, int nextWidth, int nextHeight) {
		return !closed && planVersion == version && plan == nextPlan && width == nextWidth && height == nextHeight;
	}

	public PackTargetSet reallocate(long version, RenderTargetPlan nextPlan, int nextWidth, int nextHeight) {
		return reallocate(version, nextPlan, shadowPlan, nextWidth, nextHeight);
	}

	public PackTargetSet reallocate(long version, RenderTargetPlan nextPlan, Optional<ShadowTargetPlan> nextShadowPlan,
		int nextWidth, int nextHeight) {
		if (same(version, nextPlan, nextWidth, nextHeight) && shadowPlan.equals(nextShadowPlan)) return this;
		close();
		return new PackTargetSet(device, version, nextPlan, nextShadowPlan, nextWidth, nextHeight);
	}

	public void seedFromVanilla(GpuTexture color, GpuTexture depth) {
		Objects.requireNonNull(color, "color");
		Objects.requireNonNull(depth, "depth");
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		seed(encoder, color, depth);
		encoder.submit();
	}

	public void seed(CommandEncoder encoder, GpuTexture color, GpuTexture depth) {
		Objects.requireNonNull(encoder, "encoder");
		Objects.requireNonNull(color, "color");
		Objects.requireNonNull(depth, "depth");
		Pair main = colors.get("colortex0");
		if (main != null) copy(encoder, color, main.mainTexture(), main.mainTexture().getWidth(0), main.mainTexture().getHeight(0));
		for (int index = 0; index < 3; index++) {
			Target target = depths.get("depthtex" + index);
			if (target != null) copy(encoder, depth, target.texture(), target.texture().getWidth(0), target.texture().getHeight(0));
		}
	}

	public static List<String> seedCommands(boolean hasColor, boolean hasDepth) {
		List<String> result = new ArrayList<>();
		if (hasColor) result.add("colortex0");
		if (hasDepth) {
			result.add("depthtex0");
			result.add("depthtex1");
			result.add("depthtex2");
		}
		return List.copyOf(result);
	}

	private void allocate() {
		CommandEncoder clearEncoder = device.createCommandEncoder();
		for (RenderTargetPlan.ColorTarget target : plan.colorTargets()) {
			if (target.index() != 0 && !usedColor(target.index())) continue;
			int targetWidth = scaledDimension(target.size().relativeWidth(), target.size().width(), width);
			int targetHeight = scaledDimension(target.size().relativeHeight(), target.size().height(), height);
			colors.put(target.name(), new Pair(new Target(device, create(target.name() + ".main", target.metalFormat(), targetWidth, targetHeight, target.mipmapEnabled(), target.clear(), target.clearColor(), clearEncoder)),
				new Target(device, create(target.name() + ".alt", target.metalFormat(), targetWidth, targetHeight, target.mipmapEnabled(), target.clear(), target.clearColor(), clearEncoder))));
		}
		for (int index = 0; index < 3; index++) {
			String name = "depthtex" + index;
			depths.put(name, new Target(device, create(name, GpuFormat.D32_FLOAT, width, height, false, true, Optional.empty(), clearEncoder)));
		}
		for (RenderTargetPlan.ShadowTexture target : plan.shadowColors()) {
			if (shadowPresent(target.name())) shadows.put(target.name(), new Target(device, create(target.name(), target.metalFormat(), target.resolution(), target.resolution(), target.mipmap(), target.clear(), target.clearColor(), clearEncoder)));
		}
		for (RenderTargetPlan.ShadowTexture target : plan.shadowDepths()) {
			if (shadowPresent(target.name())) shadows.put(target.name(), new Target(device, create(target.name(), target.metalFormat(), target.resolution(), target.resolution(), target.mipmap(), true, Optional.empty(), clearEncoder)));
		}
		clearEncoder.submit();
	}

	private GpuTexture create(String label, GpuFormat format, int targetWidth, int targetHeight, boolean mipmap, boolean clear,
		Optional<RenderTargetPlan.ClearColor> clearColor, CommandEncoder clearEncoder) {
		int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
		GpuTexture texture = device.createTexture(label, usage, format, targetWidth, targetHeight, 1, mipLevelCount(targetWidth, targetHeight, mipmap));
		if (clear) {
			if (format.hasDepthAspect()) clearEncoder.clearDepthTexture(texture, 0.0);
			else clearEncoder.clearColorTexture(texture, clearColor.map(value -> new Vector4f(value.red(), value.green(), value.blue(), value.alpha())).orElseGet(Vector4f::new));
		}
		return texture;
	}

	private boolean usedColor(int index) {
		return plan.passes().stream().anyMatch(pass -> pass.reads().contains(index) || pass.writes().contains(index));
	}
	private boolean usedSampler(String name) {
		return plan.passes().stream().anyMatch(pass -> pass.samplers().contains(name));
	}
	private boolean shadowPresent(String name) {
		return shadowPlan.map(value -> value.target(name).present()).orElseGet(() -> usedSampler(name));
	}
	public static int scaledDimension(boolean relative, float value, int base) {
		return Math.max(1, relative ? Math.round(base * value) : Math.round(value));
	}
	public static int mipLevelCount(int width, int height, boolean mipmap) {
		if (!mipmap) return 1;
		return 1 + Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(width, height)));
	}
	private static void copy(CommandEncoder encoder, GpuTexture source, GpuTexture destination, int width, int height) {
		encoder.copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, Math.min(width, source.getWidth(0)), Math.min(height, source.getHeight(0)));
	}
	private Pair colorPair(String name) { return required(colors.get(name), name); }
	private static <T> T required(T value, String name) { if (value == null) throw new IllegalArgumentException("missing target: " + name); return value; }
	private static Map<String, GpuTextureView> views(Map<String, Pair> values) { Map<String, GpuTextureView> result = new LinkedHashMap<>(); values.forEach((name, pair) -> result.put(name, pair.viewMain())); return Map.copyOf(result); }
	private static Map<String, GpuTextureView> singleViews(Map<String, Target> values) { Map<String, GpuTextureView> result = new LinkedHashMap<>(); values.forEach((name, target) -> result.put(name, target.view())); return Map.copyOf(result); }

	@Override public void close() {
		if (closed) return;
		closed = true;
		colors.values().forEach(Pair::close);
		depths.values().forEach(Target::close);
		shadows.values().forEach(Target::close);
	}

	public record ViewSnapshot(long generation, RenderTargetPlan plan, int width, int height,
		Map<String, ViewPair> colors) {
		public ViewSnapshot {
			Objects.requireNonNull(plan, "plan");
			colors = Map.copyOf(colors);
		}

		public Optional<ViewPair> color(String name) {
			return Optional.ofNullable(colors.get(name));
		}
	}

	public record ViewPair(View main, View alternate) {
		public ViewPair {
			Objects.requireNonNull(main, "main");
			Objects.requireNonNull(alternate, "alternate");
		}

		public View selected(boolean alternate) {
			return alternate ? this.alternate : main;
		}
	}

	public record View(String name, GpuTexture texture, GpuTextureView view) {
		public View {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(texture, "texture");
			Objects.requireNonNull(view, "view");
		}
	}

	private record Target(GpuTexture texture, GpuTextureView view) {
		Target(GpuDevice device, GpuTexture texture) { this(texture, device.createTextureView(texture)); }
		void close() { if (view != null) view.close(); if (texture != null) texture.close(); }
	}
	private record Pair(Target main, Target alt) {
		GpuTextureView viewMain() { return main.view(); }
		GpuTextureView viewAlt() { return alt.view(); }
		GpuTexture mainTexture() { return main.texture(); }
		void close() { main.close(); alt.close(); }
	}
}
