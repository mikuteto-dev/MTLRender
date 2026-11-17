package dev.mikuteto.mtlrender.pack.glsl;

public record DepthResource(String name, Family family, SamplerKind samplerKind) {
	public enum Family {
		WORLD,
		SHADOW
	}

	public enum SamplerKind {
		SAMPLE,
		COMPARE_SAMPLE,
		IMAGE
	}
}
