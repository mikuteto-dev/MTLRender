package dev.mikuteto.mtlrender.terrain;

import java.util.Objects;
import java.util.Optional;

import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

record PackTerrainRenderState(boolean packActive, long packGeneration, long metadataGeneration, long pipelineGeneration,
	long requirementsFingerprint, long blockIdMapGeneration, Optional<PackTerrainPipelines.Selection> solid,
	Optional<PackTerrainPipelines.Selection> cutout, Optional<PackTerrainPipelines.Selection> translucent) {
	private static final long NONE_GENERATION = -1L;

	PackTerrainRenderState {
		solid = clean(solid, "solid");
		cutout = clean(cutout, "cutout");
		translucent = clean(translucent, "translucent");
		if (!packActive && (!solid.isEmpty() || !cutout.isEmpty() || !translucent.isEmpty())) {
			throw new IllegalArgumentException("inactive pack state cannot select pipelines");
		}
	}

	@FunctionalInterface
	interface Probe {
		PackTerrainRenderState snapshot();
	}

	static PackTerrainRenderState select(String configured, Probe probe) {
		if (configured == null || configured.isEmpty()) return none();
		return probe.snapshot();
	}

	static PackTerrainRenderState none() {
		return new PackTerrainRenderState(false, NONE_GENERATION, NONE_GENERATION, NONE_GENERATION,
			PackTerrainRequirements.none().fingerprint(), NONE_GENERATION, Optional.empty(), Optional.empty(), Optional.empty());
	}

	static PackTerrainRenderState active(long packGeneration, PackTerrainRequirements requirements,
		long blockIdMapGeneration, Optional<PackTerrainPipelines.Selection> solid,
		Optional<PackTerrainPipelines.Selection> cutout, Optional<PackTerrainPipelines.Selection> translucent) {
		return active(packGeneration, packGeneration, pipelineGeneration(solid, cutout, translucent), requirements,
			blockIdMapGeneration, solid, cutout, translucent);
	}

	static PackTerrainRenderState active(long packGeneration, long metadataGeneration, long pipelineGeneration,
		PackTerrainRequirements requirements, long blockIdMapGeneration, Optional<PackTerrainPipelines.Selection> solid,
		Optional<PackTerrainPipelines.Selection> cutout, Optional<PackTerrainPipelines.Selection> translucent) {
		Objects.requireNonNull(requirements, "requirements");
		return new PackTerrainRenderState(true, packGeneration, metadataGeneration, pipelineGeneration, requirements.fingerprint(),
			blockIdMapGeneration, solid, cutout, translucent);
	}

	static PackTerrainRenderState capture(PackRuntimeService runtime, PackTerrainPipelines pipelines,
		TerrainPipelines vanilla, String dimension, PackRuntimeService.PackSnapshot active, long runtimeGeneration,
		PackTerrainRequirements requirements, long metadataGeneration, PackTerrainPipelines.VertexMode vertexMode,
		long blockIdMapGeneration) {
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(pipelines, "pipelines");
		Objects.requireNonNull(vanilla, "vanilla");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(requirements, "requirements");
		Objects.requireNonNull(vertexMode, "vertex mode");
		Objects.requireNonNull(active, "active pack");
		long generation = runtimeGeneration;
		if (generation == Long.MIN_VALUE) generation = active.version();
		Optional<PackTerrainPipelines.Selection> solid = pipelines.get(vanilla, dimension, ChunkSectionLayer.SOLID, requirements, vertexMode);
		Optional<PackTerrainPipelines.Selection> cutout = pipelines.get(vanilla, dimension, ChunkSectionLayer.CUTOUT, requirements, vertexMode);
		Optional<PackTerrainPipelines.Selection> translucent = pipelines.get(vanilla, dimension, ChunkSectionLayer.TRANSLUCENT, requirements, vertexMode);
		return active(generation, metadataGeneration, pipelineGeneration(solid, cutout, translucent), requirements,
			blockIdMapGeneration, solid, cutout, translucent);
	}

	Optional<PackTerrainPipelines.Selection> pipeline(ChunkSectionLayer layer) {
		return switch (Objects.requireNonNull(layer, "layer")) {
			case SOLID -> solid;
			case CUTOUT -> cutout;
			case TRANSLUCENT -> translucent;
		};
	}

	boolean isCurrent(PackRuntimeService runtime, PackTerrainRequirements requirements, long currentBlockIdMapGeneration) {
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(requirements, "requirements");
		Optional<PackRuntimeService.PackSnapshot> active = runtime.activePack();
		boolean generationMatches = active.isEmpty() || packGeneration == active.orElseThrow().version();
		if (packActive && runtime.generation() != Long.MIN_VALUE) {
			generationMatches &= packGeneration == runtime.generation();
		}
		return packActive == active.isPresent()
			&& generationMatches
			&& requirementsFingerprint == requirements.fingerprint()
			&& blockIdMapGeneration == currentBlockIdMapGeneration;
	}

	boolean metadataCompatible(DirectUberBuffer.Entry entry, long metadataBlockIdMapGeneration) {
		Objects.requireNonNull(entry, "metadata entry");
		return metadataCompatible(entry.metadataGeneration(), entry.metadataRequirements(), metadataBlockIdMapGeneration);
	}

	boolean metadataCompatible(long metadataGeneration, long metadataRequirements, long metadataBlockIdMapGeneration) {
		return packActive && this.metadataGeneration == metadataGeneration
			&& requirementsFingerprint == metadataRequirements
			&& blockIdMapGeneration == metadataBlockIdMapGeneration;
	}

	private static long pipelineGeneration(Optional<PackTerrainPipelines.Selection> solid,
		Optional<PackTerrainPipelines.Selection> cutout, Optional<PackTerrainPipelines.Selection> translucent) {
		long result = NONE_GENERATION;
		if (solid.isPresent()) result = Math.max(result, solid.orElseThrow().generation());
		if (cutout.isPresent()) result = Math.max(result, cutout.orElseThrow().generation());
		if (translucent.isPresent()) result = Math.max(result, translucent.orElseThrow().generation());
		return result;
	}

	private static <T> Optional<T> clean(Optional<T> value, String name) {
		return Objects.requireNonNull(value, name);
	}
}
