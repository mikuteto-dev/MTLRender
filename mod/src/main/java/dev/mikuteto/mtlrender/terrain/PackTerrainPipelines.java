package dev.mikuteto.mtlrender.terrain;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramId;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ids.IdMap;
import dev.mikuteto.mtlrender.pack.runtime.PackProgramArtifact;
import dev.mikuteto.mtlrender.pack.runtime.PackProgramCache;
import dev.mikuteto.mtlrender.pack.runtime.PackTerrainVertexWrapper;
import dev.mikuteto.mtlrender.pack.runtime.PackTranslationArtifact;
import dev.mikuteto.mtlrender.pack.runtime.RuntimeProgram;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class PackTerrainPipelines {
	public enum VertexMode {
		COMPACT,
		BLOCK,
		PACK
	}

	public record Artifact(PackProgramArtifact program, PackTranslationArtifact translation) {
		public Artifact {
			Objects.requireNonNull(program, "program");
			Objects.requireNonNull(translation, "translation");
		}
	}

	@FunctionalInterface
	public interface ArtifactLoader {
		Optional<Artifact> load(String dimension, ProgramId program, ChunkSectionLayer layer) throws Exception;
	}

	public record Failure(String code, String message, Throwable cause) {
		public Failure {
			Objects.requireNonNull(code, "code");
			Objects.requireNonNull(message, "message");
		}
	}

	public record Selection(long pipeline, PackPipelineDesc descriptor, PackTerrainRequirements requirements,
		long generation, VertexMode vertexMode, Optional<Failure> failure) {
		public Selection {
			if (pipeline < 0) throw new IllegalArgumentException("pipeline handle must not be negative");
			Objects.requireNonNull(descriptor, "descriptor");
			Objects.requireNonNull(requirements, "requirements");
			Objects.requireNonNull(vertexMode, "vertex mode");
			failure = failure == null ? Optional.empty() : failure;
		}

		public boolean usable() {
			return pipeline > 0 && failure.isEmpty();
		}

		public int requirementMask() {
			return requirements.sidecarMask();
		}

		public Optional<Failure> failureContext() {
			return failure;
		}
	}

	private record Key(long generation, ProgramId program, long requirements, VertexMode vertexMode,
		int renderState) {
	}

	private final Supplier<Optional<ShaderPack>> activePack;
	private final LongSupplier packVersion;
	private final Supplier<IdMap> idMap;
	private final PackProgramCache<Artifact> cache;
	private final ArtifactLoader loader;
	private final Map<Key, Selection> selections = new LinkedHashMap<>();
	private long cachedVersion = Long.MIN_VALUE;

	public PackTerrainPipelines() {
		this(PackManager.get()::active, PackManager.get()::version, () -> PackManager.get().active().map(ShaderPack::idMap).orElse(null),
		new PackProgramCache<>(PackManager.get(), ignored -> { }), (dimension, program, layer) -> Optional.empty());
	}

	public PackTerrainPipelines(Supplier<Optional<ShaderPack>> activePack, LongSupplier packVersion,
		Supplier<IdMap> idMap, PackProgramCache<Artifact> cache, ArtifactLoader loader) {
		this.activePack = Objects.requireNonNull(activePack, "active pack");
		this.packVersion = Objects.requireNonNull(packVersion, "pack version");
		this.idMap = Objects.requireNonNull(idMap, "id map");
		this.cache = Objects.requireNonNull(cache, "cache");
		this.loader = Objects.requireNonNull(loader, "artifact loader");
	}

	public Optional<Selection> get(TerrainPipelines vanilla, String dimension, ChunkSectionLayer layer,
		PackTerrainRequirements requirements, VertexMode vertexMode) {
		Objects.requireNonNull(vanilla, "vanilla");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(layer, "layer");
		Objects.requireNonNull(requirements, "requirements");
		Objects.requireNonNull(vertexMode, "vertex mode");
		if (activePack.get().isEmpty()) return Optional.empty();
		long version = packVersion.getAsLong();
		if (cachedVersion != version) {
			selections.clear();
			cachedVersion = version;
		}
		ShaderPack pack = activePack.get().orElseThrow();
		ProgramId requested = program(layer);
		Optional<Program> resolved = pack.programSet(dimension).resolve(requested);
		if (resolved.isEmpty()) return Optional.empty();
		ProgramId resolvedId = ProgramId.fromSourceName(resolved.orElseThrow().name()).orElse(requested);
		Optional<PackProgramCache<Artifact>.Generation> generation = cache.generation(dimension);
		if (generation.isEmpty()) return Optional.empty();
		long generationId = generation.orElseThrow().identity().generation();
		Key key = new Key(generationId, resolvedId, requirements.fingerprint(), vertexMode, vanilla.renderState(layer));
		Selection existing = selections.get(key);
		if (existing != null) return Optional.of(existing);
		selections.entrySet().removeIf(entry -> entry.getKey().generation() == generationId
			&& entry.getKey().program() == resolvedId);
		PackProgramCache.ProgramResult<Artifact> result = generation.orElseThrow().result(resolved.orElseThrow().name(),
			() -> PackProgramCache.ProgramResult.success(loader.load(dimension, resolvedId, layer).orElseThrow(
				() -> new IllegalStateException("terrain program artifact is unavailable"))));
		if (!result.successful()) return Optional.of(failure(generationId, requirements, vertexMode, result.failure().orElseThrow()));
		try {
			Selection selection = build(vanilla, layer, requirements, vertexMode, generationId, result.pipeline().orElseThrow());
			selections.put(key, selection);
			return Optional.of(selection);
		} catch (RuntimeException exception) {
			return Optional.of(failure(generationId, requirements, vertexMode, exception));
		}
	}

	public int cachedSelections() {
		return selections.size();
	}

	public IdMap idMap() {
		return idMap.get();
	}

	private Selection build(TerrainPipelines vanilla, ChunkSectionLayer layer, PackTerrainRequirements requirements,
		VertexMode vertexMode, long generation, Artifact artifact) {
		PackTerrainVertexWrapper.Result wrapper = PackTerrainVertexWrapper.wrap(artifact.translation());
		RuntimeProgram runtime = artifact.program().runtime();
		RenderPipeline base = vanilla.vanilla(layer);
		PackTranslationArtifact.Reflection reflection = artifact.translation().reflection();
		return new Selection(runtime.renderPipeline(), descriptor(wrapper, artifact.translation(), reflection, base, vertexMode), requirements,
			generation, vertexMode, Optional.empty());
	}

	private PackPipelineDesc descriptor(PackTerrainVertexWrapper.Result wrapper, PackTranslationArtifact translation,
		PackTranslationArtifact.Reflection reflection, RenderPipeline base, VertexMode vertexMode) {
		Map<String, PackPipelineDesc.Binding> bindings = new LinkedHashMap<>();
		for (PackTranslationArtifact.ResourceBinding binding : reflection.bindings()) {
			PackPipelineDesc.Kind kind = switch (binding.kind()) {
				case TEXTURE -> PackPipelineDesc.Kind.TEXTURE;
				case SAMPLER -> PackPipelineDesc.Kind.SAMPLER;
				case BUFFER -> PackPipelineDesc.Kind.BUFFER;
				case IMAGE -> throw new IllegalArgumentException("terrain image bindings are not supported by the render pipeline API");
			};
			bindings.put(binding.kind() + ":" + binding.name(), new PackPipelineDesc.Binding(binding.name(), kind,
				binding.slot(), binding.stages()));
		}
		ColorTargetState[] targets = base.getColorTargetStates();
		return new PackPipelineDesc(wrapper.msl(), wrapper.entry(), translation.fragmentMsl(),
			translation.fragmentEntry(), bindings.values().stream().toList(), null,
			java.util.Arrays.asList(targets), depthFormat(base), depthTest(base), depthWrite(base), cull(base));
	}

	private static GpuFormat depthFormat(RenderPipeline pipeline) {
		return pipeline.getDepthStencilState() == null ? null : GpuFormat.D32_FLOAT;
	}

	private static CompareOp depthTest(RenderPipeline pipeline) {
		return pipeline.getDepthStencilState() == null ? null : pipeline.getDepthStencilState().depthTest();
	}

	private static boolean depthWrite(RenderPipeline pipeline) {
		return pipeline.getDepthStencilState() != null && pipeline.getDepthStencilState().writeDepth();
	}

	private static PackPipelineDesc.CullMode cull(RenderPipeline pipeline) {
		return pipeline.isCull() ? PackPipelineDesc.CullMode.BACK : PackPipelineDesc.CullMode.NONE;
	}

	private static Selection failure(long generation, PackTerrainRequirements requirements,
		VertexMode vertexMode, Throwable cause) {
		String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
		return new Selection(0L, new PackPipelineDesc("failure", "failure", "failure", "failure", java.util.List.of(), null,
			java.util.List.of(), null, null, false, PackPipelineDesc.CullMode.NONE), requirements, generation, vertexMode,
			Optional.of(new Failure("ARTIFACT", message, cause)));
	}

	public static ProgramId programFor(ChunkSectionLayer layer) {
		Objects.requireNonNull(layer, "layer");
		return program(layer);
	}

	private static ProgramId program(ChunkSectionLayer layer) {
		return switch (layer) {
			case SOLID -> ProgramId.TerrainSolid;
			case CUTOUT -> ProgramId.TerrainCutout;
			case TRANSLUCENT -> ProgramId.Water;
		};
	}
}
