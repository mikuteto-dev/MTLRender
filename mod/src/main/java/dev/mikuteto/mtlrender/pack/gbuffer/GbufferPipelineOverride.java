package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import net.minecraft.client.Minecraft;

import java.util.Optional;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class GbufferPipelineOverride {
	private final Supplier<Optional<ShaderPack>> activePack;
	private final LongSupplier packVersion;
	private final Supplier<String> dimension;

	public GbufferPipelineOverride() {
		this(() -> PackManager.get().active(), () -> PackManager.get().version(), GbufferPipelineOverride::currentDimension);
	}

	public GbufferPipelineOverride(Supplier<Optional<ShaderPack>> activePack, LongSupplier packVersion,
		Supplier<String> dimension) {
		this.activePack = Objects.requireNonNull(activePack, "active pack");
		this.packVersion = Objects.requireNonNull(packVersion, "pack version");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
	}

	public Selection select(RenderPipeline pipeline, ShaderSource vanilla) {
		Objects.requireNonNull(pipeline, "pipeline");
		Objects.requireNonNull(vanilla, "vanilla");
		if (!GbufferPipelineCatalog.levelPipelines().contains(pipeline)) {
			return Selection.vanilla(vanilla);
		}
		Optional<ShaderPack> pack = activePack.get();
		if (pack.isEmpty()) return Selection.vanilla(vanilla);
		long version = packVersion.getAsLong();
		String currentDimension = dimension.get();
		if (currentDimension == null || currentDimension.isBlank()) {
			return new Selection(vanilla, true, version, "");
		}
		try {
			GbufferProgramResolver resolver = new GbufferProgramResolver(() -> pack, () -> version);
			GbufferShaderSource resolved = resolver.source(pipeline, currentDimension, vanilla);
			ShaderSource source = (id, type) -> {
				if (id.equals(pipeline.getVertexShader()) && type == com.mojang.blaze3d.shaders.ShaderType.VERTEX) {
					return resolved.get(resolved.vertex(), type);
				}
				if (id.equals(pipeline.getFragmentShader()) && type == com.mojang.blaze3d.shaders.ShaderType.FRAGMENT) {
					return resolved.get(resolved.fragment(), type);
				}
				return resolved.get(id, type);
			};
			return new Selection(source, true, version, currentDimension);
		} catch (RuntimeException exception) {
			return new Selection(vanilla, true, version, currentDimension);
		}
	}

	private static String currentDimension() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? null : minecraft.level.dimension().identifier().toString();
	}

	public record Selection(ShaderSource source, boolean active, long version, String dimension) {
		public Selection {
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(dimension, "dimension");
		}

		private static Selection vanilla(ShaderSource source) {
			return new Selection(source, false, Long.MIN_VALUE, "");
		}
	}
}
