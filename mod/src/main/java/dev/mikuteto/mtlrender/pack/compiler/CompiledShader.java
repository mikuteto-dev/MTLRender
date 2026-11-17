package dev.mikuteto.mtlrender.pack.compiler;

import dev.mikuteto.mtlrender.pack.glsl.DepthUsage;

public record CompiledShader(String msl, Reflection reflection, DepthUsage depthUsage) {
	public CompiledShader(String msl, Reflection reflection) {
		this(msl, reflection, new DepthUsage(java.util.List.of(), false));
	}
}
