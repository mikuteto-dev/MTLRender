package dev.mikuteto.mtlrender.backend;

import java.util.List;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;

record MetalPipeline(long handle, List<ShaderBinding> bindings, Kind kind, boolean packGbuffer) implements CompiledRenderPipeline {
	static final MetalPipeline INVALID = invalid(Kind.RASTER);

	static MetalPipeline invalid(Kind kind) {
		return new MetalPipeline(0, List.of(), kind, false);
	}

	enum Kind {
		RASTER,
		MESH
	}

	@Override
	public boolean isValid() {
		return handle != 0;
	}
}
