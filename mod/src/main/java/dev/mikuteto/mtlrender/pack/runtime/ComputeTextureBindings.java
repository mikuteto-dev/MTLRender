package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.texture.TextureReference;
import dev.mikuteto.mtlrender.pack.texture.TextureSpec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ComputeTextureBindings implements AutoCloseable {
	public interface Factory {
		GpuTexture createTexture(GpuFormat format, int width, int height, int depth, String label);
		GpuTextureView createView(GpuTexture texture);
		GpuSampler createSampler(boolean blur, boolean clamp);
		void write(GpuTexture texture, ByteBuffer pixels, int depth, int width, int height);
	}

	@FunctionalInterface
	public interface ResourceResolver {
		ResolvedTexture resolve(TextureReference reference) throws IOException;
	}

	public record ResolvedTexture(GpuTextureView view, GpuSampler sampler, int width, int height, int depth) {
		public ResolvedTexture {
			Objects.requireNonNull(view, "view");
			Objects.requireNonNull(sampler, "sampler");
			if (width <= 0 || height <= 0 || depth <= 0) throw new IllegalArgumentException("invalid resolved texture shape");
		}
	}

	public record UploadPlan(String logicalName, String samplerName, TextureSpec.Target target, GpuFormat format,
		int width, int height, int depth, int rowStride, int sliceStride, boolean blur, boolean clamp, boolean reference) {
		public UploadPlan {
			Objects.requireNonNull(logicalName, "logical name");
			Objects.requireNonNull(samplerName, "sampler name");
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(format, "format");
			if (width <= 0 || height <= 0 || depth <= 0 || rowStride <= 0 || sliceStride <= 0) {
				throw new IllegalArgumentException("invalid texture upload plan");
			}
		}
	}

	public record Binding(String logicalName, String samplerName, GpuTextureView view, GpuSampler sampler, UploadPlan plan) {
		public Binding {
			Objects.requireNonNull(logicalName, "logical name");
			Objects.requireNonNull(samplerName, "sampler name");
			Objects.requireNonNull(view, "view");
			Objects.requireNonNull(sampler, "sampler");
			Objects.requireNonNull(plan, "plan");
		}
	}

	private final Map<String, Binding> bindings;
	private final List<Owned> owned;

	public ComputeTextureBindings(List<TextureSpec> specs, Factory factory, ResourceResolver resolver) throws IOException {
		Objects.requireNonNull(specs, "specs");
		Objects.requireNonNull(factory, "factory");
		Objects.requireNonNull(resolver, "resolver");
		Map<String, Binding> result = new LinkedHashMap<>();
		List<Owned> created = new ArrayList<>();
		try {
			for (TextureSpec spec : specs) {
				if (result.containsKey(spec.sampler())) throw new IOException("duplicate texture sampler " + spec.sampler());
				UploadPlan plan = plan(spec);
				GpuTextureView view;
				GpuSampler sampler;
				if (spec.reference().isPresent()) {
					ResolvedTexture resolved = resolver.resolve(spec.reference().orElseThrow());
					if (resolved == null) throw new IOException("missing referenced texture " + spec.reference().orElseThrow().location());
					view = resolved.view();
					sampler = resolved.sampler();
					plan = new UploadPlan(spec.sampler(), spec.sampler(), spec.target(), plan.format(), resolved.width(), resolved.height(),
						resolved.depth(), plan.rowStride(), plan.sliceStride(), spec.blur(), spec.clamp(), true);
				} else {
					GpuTexture texture = factory.createTexture(plan.format(), plan.width(), plan.height(), plan.depth(), "pack texture " + spec.sampler());
					view = factory.createView(texture);
					sampler = factory.createSampler(spec.blur(), spec.clamp());
					created.add(new Owned(view, sampler, texture));
					byte[] pixels = spec.pixels();
					if (pixels.length < plan.sliceStride() * plan.depth()) throw new IOException("texture upload is shorter than its plan: " + spec.sampler());
					for (int depth = 0; depth < plan.depth(); depth++) {
						ByteBuffer slice = ByteBuffer.wrap(pixels, depth * plan.sliceStride(), plan.sliceStride()).slice();
						factory.write(texture, slice, depth, plan.width(), plan.height());
					}
				}
				result.put(spec.sampler(), new Binding(spec.sampler(), spec.sampler(), view, sampler, plan));
			}
		} catch (RuntimeException | IOException exception) {
			close(created);
			throw exception;
		}
		bindings = Map.copyOf(result);
		owned = List.copyOf(created);
	}

	public static Cache cache(Factory factory, ResourceResolver resolver) {
		return new Cache(factory, resolver);
	}

	public static ComputeTextureBindings metal(List<TextureSpec> specs, ResourceResolver resolver) throws IOException {
		return new ComputeTextureBindings(specs, new Factory() {
			@Override public GpuTexture createTexture(GpuFormat format, int width, int height, int depth, String label) {
				return MetalAccess.createPackTexture(format, width, height, depth, label);
			}
			@Override public GpuTextureView createView(GpuTexture texture) { return MetalAccess.createStorageImageView(texture); }
			@Override public GpuSampler createSampler(boolean blur, boolean clamp) { return MetalAccess.createPackSampler(blur, clamp); }
			@Override public void write(GpuTexture texture, ByteBuffer pixels, int depth, int width, int height) {
				MetalAccess.writePackTexture(texture, pixels, depth, width, height);
			}
		}, resolver);
	}

	public static UploadPlan plan(TextureSpec spec) {
		Objects.requireNonNull(spec, "spec");
		if (spec.reference().isPresent()) {
			return new UploadPlan(spec.sampler(), spec.sampler(), spec.target(), GpuFormat.RGBA8_UNORM,
				1, 1, 1, 4, 4, spec.blur(), spec.clamp(), true);
		}
		GpuFormat format = format(spec.internalFormat());
		int components = components(spec.pixelFormat());
		int componentBytes = componentBytes(spec.pixelType());
		int rowStride = Math.multiplyExact(Math.multiplyExact(spec.width(), components), componentBytes);
		return new UploadPlan(spec.sampler(), spec.sampler(), spec.target(), format, spec.width(), spec.height(), spec.depth(),
			rowStride, Math.multiplyExact(rowStride, spec.height()), spec.blur(), spec.clamp(), false);
	}

	public Optional<Binding> find(String name) { return Optional.ofNullable(bindings.get(name)); }
	public Optional<Binding> forResource(Reflection.Resource resource) { return find(resource.name()); }
	public Map<String, Binding> bindings() { return bindings; }
	public List<UploadPlan> uploadPlans() { return bindings.values().stream().map(Binding::plan).toList(); }

	@Override public void close() { close(owned); }

	private static void close(List<Owned> values) {
		for (Owned value : values) {
			value.view().close();
			value.sampler().close();
			value.texture().close();
		}
	}

	private static GpuFormat format(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("texture format is missing");
		String mapped = value.toUpperCase(java.util.Locale.ROOT);
		if (mapped.equals("RGBA8")) return GpuFormat.RGBA8_UNORM;
		if (mapped.endsWith("F")) mapped = mapped.substring(0, mapped.length() - 1) + "_FLOAT";
		else if (mapped.endsWith("I")) mapped = mapped.substring(0, mapped.length() - 1) + "_SINT";
		else if (mapped.endsWith("UI")) mapped = mapped.substring(0, mapped.length() - 2) + "_UINT";
		else if (!mapped.endsWith("_UNORM") && !mapped.endsWith("_SNORM")) mapped += "_UNORM";
		return GpuFormat.valueOf(mapped);
	}

	private static int components(String value) {
		return switch (value) {
			case "RED", "RED_INTEGER" -> 1;
			case "RG", "RG_INTEGER" -> 2;
			case "RGB", "BGR", "RGB_INTEGER", "BGR_INTEGER" -> 3;
			case "RGBA", "BGRA", "RGBA_INTEGER", "BGRA_INTEGER" -> 4;
			default -> throw new IllegalArgumentException("unsupported texture pixel format " + value);
		};
	}

	private static int componentBytes(String value) {
		return switch (value) {
			case "BYTE", "UNSIGNED_BYTE", "UNSIGNED_BYTE_3_3_2", "UNSIGNED_BYTE_2_3_3_REV" -> 1;
			case "SHORT", "HALF_FLOAT", "UNSIGNED_SHORT", "UNSIGNED_SHORT_5_6_5", "UNSIGNED_SHORT_5_6_5_REV",
				"UNSIGNED_SHORT_4_4_4_4", "UNSIGNED_SHORT_4_4_4_4_REV", "UNSIGNED_SHORT_5_5_5_1", "UNSIGNED_SHORT_1_5_5_5_REV" -> 2;
			case "INT", "FLOAT", "UNSIGNED_INT", "UNSIGNED_INT_8_8_8_8", "UNSIGNED_INT_8_8_8_8_REV",
				"UNSIGNED_INT_10_10_10_2", "UNSIGNED_INT_2_10_10_10_REV", "UNSIGNED_INT_10F_11F_11F_REV", "UNSIGNED_INT_5_9_9_9_REV" -> 4;
			default -> throw new IllegalArgumentException("unsupported texture pixel type " + value);
		};
	}

	private static List<SpecKey> keys(List<TextureSpec> specs) {
		return specs.stream().map(SpecKey::new).toList();
	}

	public static final class Cache implements AutoCloseable {
		private final Factory factory;
		private final ResourceResolver resolver;
		private List<SpecKey> keys = List.of();
		private ComputeTextureBindings current;

		private Cache(Factory factory, ResourceResolver resolver) {
			this.factory = Objects.requireNonNull(factory, "factory");
			this.resolver = Objects.requireNonNull(resolver, "resolver");
		}

		public synchronized ComputeTextureBindings reload(List<TextureSpec> specs) throws IOException {
			List<SpecKey> nextKeys = keys(specs);
			if (current != null && keys.equals(nextKeys)) return current;
			ComputeTextureBindings next = new ComputeTextureBindings(specs, factory, resolver);
			ComputeTextureBindings old = current;
			current = next;
			keys = nextKeys;
			if (old != null) old.close();
			return next;
		}

		@Override public synchronized void close() {
			if (current != null) current.close();
			current = null;
			keys = List.of();
		}
	}

	private record SpecKey(String sampler, TextureSpec.Kind kind, TextureSpec.Target target, int width, int height, int depth,
		String internalFormat, String pixelFormat, String pixelType, boolean blur, boolean clamp, int pixelsHash,
		Optional<TextureReference> reference) {
		private SpecKey(TextureSpec spec) {
			this(spec.sampler(), spec.kind(), spec.target(), spec.width(), spec.height(), spec.depth(), spec.internalFormat(),
				spec.pixelFormat(), spec.pixelType(), spec.blur(), spec.clamp(), java.util.Arrays.hashCode(spec.pixels()), spec.reference());
		}
	}

	private record Owned(GpuTextureView view, GpuSampler sampler, GpuTexture texture) {}
}
