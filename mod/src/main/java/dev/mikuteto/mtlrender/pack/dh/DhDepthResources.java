package dev.mikuteto.mtlrender.pack.dh;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.runtime.PackTargetSet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DhDepthResources {
	public enum SamplerPolicy {
		NEAREST_CLAMP_NO_MIP_NO_COMPARE
	}

	public record Resource(GpuTextureView view, SamplerPolicy samplerPolicy) {
		public Resource {
			Objects.requireNonNull(view, "view");
			Objects.requireNonNull(samplerPolicy, "sampler policy");
		}
	}

	private static final Set<String> NAMES = Set.of("dhDepthTex", "dhDepthTex0", "dhDepthTex1");
	private final Map<String, Resource> resources;
	private final long generation;

	private DhDepthResources(long generation, Map<String, Resource> resources) {
		if (generation < 0L) throw new IllegalArgumentException("DH depth generation must be non-negative");
		this.generation = generation;
		this.resources = Map.copyOf(new LinkedHashMap<>(resources));
	}

	public static DhDepthResources from(PackTargetSet targets) {
		Objects.requireNonNull(targets, "targets");
		Map<String, Resource> values = new LinkedHashMap<>();
		Optional<GpuTextureView> depth0 = targets.optionalDhDepth(0);
		Optional<GpuTextureView> depth1 = targets.optionalDhDepth(1);
		depth0.map(view -> new Resource(view, SamplerPolicy.NEAREST_CLAMP_NO_MIP_NO_COMPARE))
			.ifPresent(value -> {
				values.put("dhDepthTex", value);
				values.put("dhDepthTex0", value);
			});
		depth1.map(view -> new Resource(view, SamplerPolicy.NEAREST_CLAMP_NO_MIP_NO_COMPARE))
			.ifPresent(value -> values.put("dhDepthTex1", value));
		return new DhDepthResources(targets.planVersion(), values);
	}

	public static DhDepthResources of(Map<String, Resource> resources) {
		return of(0L, resources);
	}

	public static DhDepthResources of(long generation, Map<String, Resource> resources) {
		Objects.requireNonNull(resources, "resources");
		Map<String, Resource> values = new LinkedHashMap<>();
		resources.forEach((name, resource) -> {
			if (!NAMES.contains(name)) throw new IllegalArgumentException("unknown DH depth sampler: " + name);
			values.put(name, Objects.requireNonNull(resource, "resource"));
		});
		Resource depth0 = values.get("dhDepthTex0");
		if (depth0 != null) values.putIfAbsent("dhDepthTex", depth0);
		return new DhDepthResources(generation, values);
	}

	public long generation() {
		return generation;
	}

	public Optional<Resource> resource(String name) {
		if (!NAMES.contains(name)) throw new IllegalArgumentException("unknown DH depth sampler: " + name);
		return Optional.ofNullable(resources.get(name));
	}

	public Map<String, Resource> resources() {
		return resources;
	}

	public boolean isEmpty() {
		return resources.isEmpty();
	}
}
