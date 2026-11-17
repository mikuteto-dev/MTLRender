package dev.mikuteto.mtlrender.pack.glsl;

import java.util.List;

public record DepthUsage(List<DepthResource> resources, boolean writesFragmentDepth) {
	public DepthUsage {
		resources = List.copyOf(resources);
	}

	public boolean hasWorldDepth() {
		return resources.stream().anyMatch(resource -> resource.family() == DepthResource.Family.WORLD);
	}

	public boolean hasShadowDepth() {
		return resources.stream().anyMatch(resource -> resource.family() == DepthResource.Family.SHADOW);
	}
}
