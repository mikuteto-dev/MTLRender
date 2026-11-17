package dev.mikuteto.mtlrender.backend;

import com.mojang.blaze3d.GpuFormat;

record ShaderBinding(Kind kind, String name, GpuFormat texelFormat, boolean vertex, boolean fragment) {
	static final int STAGE_VERTEX = 1;
	static final int STAGE_FRAGMENT = 2;

	int stageMask() {
		return (vertex ? STAGE_VERTEX : 0) | (fragment ? STAGE_FRAGMENT : 0);
	}

	enum Kind {
		UNIFORM_BUFFER,
		SAMPLED_IMAGE,
		TEXEL_BUFFER
	}
}
