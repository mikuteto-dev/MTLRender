package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import org.joml.Vector4f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ComputeResources implements AutoCloseable {
	public record TargetSize(int renderWidth, int renderHeight, int shadowWidth, int shadowHeight) {
		public TargetSize {
			if (renderWidth <= 0 || renderHeight <= 0 || shadowWidth <= 0 || shadowHeight <= 0) {
				throw new IllegalArgumentException("target dimensions must be positive");
			}
		}
	}

	public record ResolvedDimensions(int width, int height, int depth) {
		public ResolvedDimensions {
			if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("dimensions must be positive");
		}
	}

	public record GeometryAllocation(GeometryComputePlan.Context context, long size, Resource resource) {
		public GeometryAllocation {
			Objects.requireNonNull(context, "context");
			if (size <= 0) throw new IllegalArgumentException("geometry allocation size must be positive");
			Objects.requireNonNull(resource, "resource");
		}
	}

	public interface Factory {
		Resource buffer(String name, long size);
		Resource image(String name, GpuFormat format, ResolvedDimensions dimensions);
		default void clear(Resource resource) { }
	}

	public static class Resource implements AutoCloseable {
		private final GpuBuffer buffer;
		private final GpuTexture texture;
		private final GpuTextureView view;

		public Resource(GpuBuffer buffer, GpuTexture texture, GpuTextureView view) {
			this.buffer = buffer;
			this.texture = texture;
			this.view = view;
		}

		public GpuBuffer buffer() { return buffer; }
		public GpuTexture texture() { return texture; }
		public GpuTextureView view() { return view; }
		@Override public void close() {
			if (view != null) view.close();
			if (texture != null) texture.close();
			if (buffer != null) buffer.close();
		}
	}

	private static final Factory METAL = new Factory() {
		@Override public Resource buffer(String name, long size) {
			return new Resource(MetalAccess.createStorageBuffer(size), null, null);
		}
		@Override public Resource image(String name, GpuFormat format, ResolvedDimensions dimensions) {
			GpuTexture texture = MetalAccess.createStorageImage(format, dimensions.width(), dimensions.height(), dimensions.depth(), 1);
			try {
				return new Resource(null, texture, MetalAccess.createStorageImageView(texture));
			} catch (RuntimeException exception) {
				texture.close();
				throw exception;
			}
		}
		@Override public void clear(Resource resource) {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.clearColorTexture(resource.texture(), new Vector4f());
			encoder.submit();
		}
	};

	private final Factory factory;
	private final Map<String, Entry> ssbos = new LinkedHashMap<>();
	private final Map<String, Entry> images = new LinkedHashMap<>();
	private final Map<String, GeometryAllocation> geometry = new LinkedHashMap<>();
	private TargetSize targets;
	private boolean closed;

	public ComputeResources(Map<Integer, ComputePlan.SsboSpec> ssboSpecs,
		Map<String, ComputePlan.CustomImageSpec> imageSpecs, TargetSize targets) {
		this(METAL, ssboSpecs, imageSpecs, targets);
	}

	public ComputeResources(Factory factory, Map<Integer, ComputePlan.SsboSpec> ssboSpecs,
		Map<String, ComputePlan.CustomImageSpec> imageSpecs, TargetSize targets) {
		this.factory = Objects.requireNonNull(factory, "factory");
		this.targets = Objects.requireNonNull(targets, "targets");
		reconcile(ssboSpecs, imageSpecs, targets);
	}

	public TargetSize targets() { return targets; }
	public Optional<GpuBuffer> ssbo(String name) { return resource(ssbos, name).map(entry -> entry.resource().buffer()); }
	public Optional<GpuBuffer> ssbo(int slot) { return ssbos.values().stream().filter(entry -> entry.slot == slot).map(entry -> entry.resource.buffer()).findFirst(); }
	public Optional<GpuTextureView> image(String name) { return resource(images, name).map(entry -> entry.resource().view()); }
	public Optional<GpuTextureView> image(int slot) { return images.values().stream().filter(entry -> entry.slot == slot).map(entry -> entry.resource.view()).findFirst(); }
	public Optional<ResolvedDimensions> imageDimensions(String name) { return resource(images, name).map(Entry::dimensions); }
	public Optional<Long> ssboSize(String name) { return resource(ssbos, name).map(entry -> entry.size); }

	public void reconcile(Map<Integer, ComputePlan.SsboSpec> ssboSpecs,
		Map<String, ComputePlan.CustomImageSpec> imageSpecs, TargetSize nextTargets) {
		if (closed) throw new IllegalStateException("compute resources are closed");
		Objects.requireNonNull(ssboSpecs, "ssboSpecs");
		Objects.requireNonNull(imageSpecs, "imageSpecs");
		Objects.requireNonNull(nextTargets, "targets");
		Map<String, Entry> nextSsbos = new LinkedHashMap<>();
		Map<String, Entry> nextImages = new LinkedHashMap<>();
		try {
			for (ComputePlan.SsboSpec spec : ssboSpecs.values()) {
				long size = resolvedBufferSize(spec);
				Entry old = ssbos.get(spec.name());
				nextSsbos.put(spec.name(), old != null && old.matches(spec, size, null) ? old : createBuffer(spec, size));
			}
			for (ComputePlan.CustomImageSpec spec : imageSpecs.values()) {
				GpuFormat format = format(spec);
				ResolvedDimensions dimensions = resolve(spec, nextTargets);
				Entry old = images.get(spec.name());
				nextImages.put(spec.name(), old != null && old.matches(spec, 0, new Shape(format, dimensions)) ? old : createImage(spec, format, dimensions));
			}
		} catch (RuntimeException exception) {
			closeNew(nextSsbos, ssbos);
			closeNew(nextImages, images);
			throw exception;
		}
		closeReplaced(ssbos, nextSsbos);
		closeReplaced(images, nextImages);
		ssbos.clear();
		ssbos.putAll(nextSsbos);
		images.clear();
		images.putAll(nextImages);
		targets = nextTargets;
	}

	public GpuBuffer reflectedSsbo(String name) {
		return required(ssbos, name).resource().buffer();
	}

	public GpuTextureView reflectedImage(String name) {
		return required(images, name).resource().view();
	}

	public boolean hasSsbo(String name) { return ssbos.containsKey(name); }

	public boolean hasImage(String name) { return images.containsKey(name); }

	public GeometryAllocation prepareGeometry(GeometryComputePlan plan) {
		if (closed) throw new IllegalStateException("compute resources are closed");
		Objects.requireNonNull(plan, "geometry plan");
		String key = geometryKey(plan.context());
		long size = geometrySize(plan);
		GeometryAllocation old = geometry.get(key);
		if (old != null && old.size() == size) return old;
		GeometryAllocation next = new GeometryAllocation(plan.context(), size, factory.buffer("geometry/" + key, size));
		if (old != null) old.resource().close();
		geometry.put(key, next);
		return next;
	}

	public Optional<GeometryAllocation> geometry(GeometryComputePlan.Context context) {
		return Optional.ofNullable(geometry.get(geometryKey(Objects.requireNonNull(context, "context"))));
	}

	public static ResolvedDimensions resolve(ComputePlan.CustomImageSpec spec, TargetSize targets) {
		Objects.requireNonNull(spec, "spec");
		Objects.requireNonNull(targets, "targets");
		if (!spec.relative()) return new ResolvedDimensions(spec.size().width(), spec.size().height(), spec.size().depth());
		return new ResolvedDimensions(scale(spec.relativeWidth(), targets.renderWidth()), scale(spec.relativeHeight(), targets.renderHeight()), 1);
	}

	public static ComputePlan.Dimensions resolve(ComputePlan.DispatchSpec dispatch, TargetSize targets) {
		Objects.requireNonNull(dispatch, "dispatch");
		Objects.requireNonNull(targets, "targets");
		return switch (dispatch) {
			case ComputePlan.Absolute absolute -> absolute.groups();
			case ComputePlan.RenderRelative relative -> new ComputePlan.Dimensions(relative.width(), relative.height(), 1);
			case ComputePlan.ShadowRelative relative -> new ComputePlan.Dimensions(relative.width(), relative.height(), 1);
			case ComputePlan.Default ignored -> new ComputePlan.Dimensions(targets.renderWidth(), targets.renderHeight(), 1);
			case ComputePlan.Indirect ignored -> throw new IllegalArgumentException("indirect dispatch has no dimensions");
		};
	}

	public static GpuFormat format(ComputePlan.CustomImageSpec spec) {
		Objects.requireNonNull(spec, "spec");
		String value = normalize(spec.format());
		String internal = normalize(spec.internalFormat());
		String type = normalize(spec.pixelType());
		GpuFormat result = switch (value) {
			case "R16UI" -> GpuFormat.R16_UINT;
			case "R32UI" -> GpuFormat.R32_UINT;
			case "R11G11B10F", "R11G11B10_FLOAT" -> GpuFormat.RG11B10_FLOAT;
			case "R16F" -> GpuFormat.R16_FLOAT;
			case "RGBA8", "RGBA8UNORM" -> GpuFormat.RGBA8_UNORM;
			case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
			default -> throw invalidFormat(spec);
		};
		if (!compatible(result, internal, type)) throw invalidFormat(spec);
		return result;
	}

	@Override public void close() {
		if (closed) return;
		closed = true;
		ssbos.values().forEach(entry -> entry.resource.close());
		images.values().forEach(entry -> entry.resource.close());
		ssbos.clear();
		images.clear();
		geometry.values().forEach(value -> value.resource().close());
		geometry.clear();
	}

	private Entry createBuffer(ComputePlan.SsboSpec spec, long size) {
		try { return new Entry(spec.name(), spec.slot(), size, null, factory.buffer(spec.name(), size)); }
		catch (RuntimeException exception) { throw failure(spec.name(), size + " bytes", "SSBO", exception); }
	}
	private Entry createImage(ComputePlan.CustomImageSpec spec, GpuFormat format, ResolvedDimensions dimensions) {
		try {
			Resource resource = factory.image(spec.name(), format, dimensions);
			if (spec.clear()) factory.clear(resource);
			return new Entry(spec.name(), spec.slot(), 0, new Shape(format, dimensions), resource);
		} catch (RuntimeException exception) { throw failure(spec.name(), dimensions + " " + format, "image", exception); }
	}
	private static long geometrySize(GeometryComputePlan plan) {
		long end = 0;
		for (GeometryComputePlan.Buffer buffer : plan.buffers()) {
			try { end = Math.max(end, Math.addExact(buffer.offset(), buffer.size())); }
			catch (ArithmeticException exception) { throw new IllegalArgumentException("geometry allocation size overflow", exception); }
		}
		if (end <= 0) throw new IllegalArgumentException("geometry plan has no buffers");
		return end;
	}
	private static String geometryKey(GeometryComputePlan.Context context) {
		return context.pack() + "\u0000" + context.dimension() + "\u0000" + context.program();
	}
	private static long resolvedBufferSize(ComputePlan.SsboSpec spec) {
		if (!spec.relative()) return spec.sizeBytes();
		return Math.max(1L, Math.round(spec.sizeBytes() * spec.scaleX() * spec.scaleY()));
	}
	private static int scale(float value, int base) { return Math.max(1, Math.round(value * base)); }
	private static String normalize(String value) { return value.toUpperCase().replace("_", ""); }
	private static boolean compatible(GpuFormat format, String internal, String type) {
		return switch (format) {
			case R16_UINT, R32_UINT -> (internal.equals(normalize(format == GpuFormat.R16_UINT ? "R16UI" : "R32UI")) || internal.equals("UI")) && type.equals("UINT");
			case RG11B10_FLOAT -> (internal.equals("R11G11B10F") || internal.equals("R11G11B10FLOAT") || internal.equals("R11FG11FB10F")) && type.equals("FLOAT");
			case R16_FLOAT -> (internal.equals("R16F") || internal.equals("R16FLOAT")) && type.equals("FLOAT");
			case RGBA8_UNORM -> (internal.equals("RGBA8") || internal.equals("RGBA8UNORM")) && (type.equals("UNSIGNEDBYTE") || type.equals("UBYTE"));
			case RGBA16_FLOAT -> (internal.equals("RGBA16F") || internal.equals("RGBA16FLOAT")) && type.equals("FLOAT");
			default -> false;
		};
	}
	private static IllegalArgumentException invalidFormat(ComputePlan.CustomImageSpec spec) { return new IllegalArgumentException("unsupported image format " + spec.name() + ": " + spec.format() + "/" + spec.internalFormat() + "/" + spec.pixelType()); }
	private static RuntimeException failure(String name, Object shape, String kind, RuntimeException cause) { return new IllegalStateException("failed to create " + kind + " " + name + " (" + shape + ")", cause); }
	private static Optional<Entry> resource(Map<String, Entry> map, String name) { return Optional.ofNullable(map.get(name)); }
	private static Entry required(Map<String, Entry> map, String name) { Entry entry = map.get(name); if (entry == null) throw new IllegalArgumentException("missing compute resource: " + name); return entry; }
	private static void closeNew(Map<String, Entry> next, Map<String, Entry> old) { next.values().stream().filter(entry -> !old.containsValue(entry)).forEach(entry -> entry.resource.close()); }
	private static void closeReplaced(Map<String, Entry> old, Map<String, Entry> next) { old.values().stream().filter(entry -> !next.containsValue(entry)).forEach(entry -> entry.resource.close()); }

	private record Shape(GpuFormat format, ResolvedDimensions dimensions) {}
	private record Entry(String name, int slot, long size, Shape shape, Resource resource) {
		boolean matches(ComputePlan.SsboSpec spec, long nextSize, Shape nextShape) { return name.equals(spec.name()) && slot == spec.slot() && size == nextSize && shape == null; }
		boolean matches(ComputePlan.CustomImageSpec spec, long ignored, Shape nextShape) { return name.equals(spec.name()) && slot == spec.slot() && nextShape.equals(shape); }
		ResolvedDimensions dimensions() { return shape == null ? null : shape.dimensions(); }
	}
}
