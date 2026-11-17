package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.Direction;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mikuteto.mtlrender.blockentity.BlockEntityBakeHooks;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakeKey;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakeMetadata;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakePublication;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakePart;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakePlan;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakeRegistry;
import dev.mikuteto.mtlrender.blockentity.VanillaBlockEntityBakeSet;
import dev.mikuteto.mtlrender.debug.StaticBlockEntityBakeStats;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import dev.mikuteto.mtlrender.mixin.SectionCompilerAccess;
import dev.mikuteto.mtlrender.mixin.SectionCompilerResultsAccess;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

public final class RustSectionCompiler {
	private static final int STRIDE = 28;

	private RustSectionCompiler() {
	}

	public static SectionCompiler.Results batchResults(ByteBuffer mesh, SectionBufferBuilderPack buffers) {
		SectionCompiler.Results results = new SectionCompiler.Results();
		ByteBuffer source = mesh.duplicate().order(ByteOrder.nativeOrder());
		int first = source.getInt();
		boolean metadata = first == 0x4d50_4832;
		int[] sizes = metadata
			? new int[] {source.getInt(12), source.getInt(16), source.getInt(20)}
			: new int[] {first, source.getInt(), source.getInt()};
		int[] metadataSizes = metadata
			? new int[] {source.getInt(24), source.getInt(28), source.getInt(32)}
			: new int[] {0, 0, 0};
		int dataStart = metadata ? 36 : 12;
		source.position(dataStart);
		ChunkSectionLayer[] layers = ChunkSectionLayer.values();
		for (int index = 0; index < sizes.length && index < layers.length; index++) {
			int size = sizes[index];
			if (size < 0 || size % STRIDE != 0 || metadataSizes[index] < 0
				|| metadataSizes[index] % PackTerrainLayout.RECORD_STRIDE != 0
				|| metadataSizes[index] / PackTerrainLayout.RECORD_STRIDE != size / STRIDE) return results;
			if (size <= 0) {
				continue;
			}
			ByteBufferBuilder target = buffers.buffer(layers[index]);
			long address = target.reserve(size);
			ByteBuffer destination = MemorySegment.ofAddress(address).reinterpret(size).asByteBuffer();
			ByteBuffer bytes = source.slice(source.position(), size);
			destination.put(bytes);
			source.position(source.position() + size);
			ByteBufferBuilder.Result vertexBuffer = target.build();
			if (vertexBuffer != null) {
				int vertices = size / STRIDE;
				results.renderedLayers.put(layers[index], new MeshData(
					vertexBuffer,
					new MeshData.DrawState(DefaultVertexFormat.BLOCK, vertices, vertices / 4 * 6,
						PrimitiveTopology.QUADS, IndexType.least(vertices))
				));
			}
		}
		if (metadata) {
			int metadataBytes = metadataSizes[0] + metadataSizes[1] + metadataSizes[2];
			if (source.remaining() < metadataBytes) return new SectionCompiler.Results();
			ChunkSectionLayer[] metadataLayers = ChunkSectionLayer.values();
			SectionCompilerResultsAccess resultAccess = (SectionCompilerResultsAccess) (Object) results;
			for (int index = 0; index < metadataSizes.length && index < metadataLayers.length; index++) {
				int size = metadataSizes[index];
				if (size > 0) {
					PackTerrainJavaMesh metadataBuffer = new PackTerrainJavaMesh(RustMesher.packRequirements().sidecarMask());
					metadataBuffer.append(source.slice(source.position(), size));
					resultAccess.mtlrender$setPackTerrainMetadata(metadataLayers[index], metadataBuffer);
					source.position(source.position() + size);
				}
			}
		}
		return results;
	}

	public static SectionCompiler.Results compile(
		SectionCompiler compiler,
		SectionPos sectionPos,
		RenderSectionRegion region,
		com.mojang.blaze3d.vertex.VertexSorting vertexSorting,
		SectionBufferBuilderPack buffers
	) {
		return compile(compiler, sectionPos, region, vertexSorting, buffers, null);
	}

	public static SectionCompiler.Results compile(
		SectionCompiler compiler,
		SectionPos sectionPos,
		RenderSectionRegion region,
		com.mojang.blaze3d.vertex.VertexSorting vertexSorting,
		SectionBufferBuilderPack buffers,
		SectionBatch.Scope batchScope
	) {
		long timingStart = ChunkTiming.enabled() ? System.nanoTime() : 0L;
		long dynamicStart = 0L;
		long assemblyStart = 0L;
		SectionCompilerAccess access = (SectionCompilerAccess) compiler;
		SectionCompiler.Results results = new SectionCompiler.Results();
		boolean packMetadata = RustMesher.packRequirements().needsExtraData();
		BlockPos minPos = sectionPos.origin();
		BlockPos maxPos = minPos.offset(15, 15, 15);
		VisGraph visGraph = new VisGraph();
		BlockModelLighter.enableCaching();
		try {
			StaticBlockEntityBakeRegistry bakeRegistry = VanillaBlockEntityBakeSet.registry();
			long bakeGeneration = bakeRegistry.generation().orElse(-1L);
			ModelBlockRenderer blockRenderer = new ModelBlockRenderer(access.mtlrender$ambientOcclusion(), true, access.mtlrender$blockColors());
			FluidRenderer fluidRenderer = new FluidRenderer(access.mtlrender$fluidModelSet());
			EnumMap<ChunkSectionLayer, LayerOutput> layers = new EnumMap<>(ChunkSectionLayer.class);
			Set<Long> staticPositions = new HashSet<>();
			Set<Long> dynamicPositions = new HashSet<>();
			List<StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance> publications = new ArrayList<>();
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				layers.put(layer, new LayerOutput(packMetadata ? RustMesher.packRequirements().sidecarMask() : 0));
			}
			BlockPos[] fluidPosition = new BlockPos[1];
			int[] fluidStateId = new int[1];
			FluidRenderer.Output fluidOutput = layer -> layers.get(layer).fluidVertices(fluidPosition[0], fluidStateId[0], packMetadata);

			ByteBuffer rustMesh = null;
			boolean nativeBatch = false;
			SectionBatch.Result batchResult = batchScope == null ? null : batchScope.take(sectionPos);
			dynamicStart = ChunkTiming.enabled() ? System.nanoTime() : 0L;
			try {
				if (batchResult != null && batchResult.status() == SectionBatch.Status.NATIVE
					&& batchResult.value() != null) {
					SectionCompiler.Results batchValue = batchResult.value();
					if (!addBatchMeshes(layers, batchValue, packMetadata)) {
						if (ChunkTiming.enabled()) {
							ChunkTiming.recordMesherFallback();
						}
						batchResult.release();
						batchResult = null;
					}
					else {
						nativeBatch = true;
					}
				}
				if (rustMesh == null && !nativeBatch) {
					rustMesh = RustMesher.meshSection(
						sectionPos,
						region,
						access.mtlrender$ambientOcclusion(),
						access.mtlrender$cutoutLeaves(),
						access.mtlrender$blockColors()
					);
				}
			boolean useRust = nativeBatch || rustMesh != null;
			for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
				BlockState state = region.getBlockState(pos);
				if (state.isAir()) {
					continue;
				}
				if (state.isSolidRender()) {
					visGraph.setOpaque(pos);
				}
				if (state.hasBlockEntity()) {
					BlockEntity blockEntity = region.getBlockEntity(pos);
					if (blockEntity != null) {
						results.blockEntities.add(blockEntity);
						Optional<StaticBlockEntityBakePlan> plan = bakePlan(bakeRegistry, bakeGeneration, blockEntity);
						if (plan.isPresent() && !plan.get().fallbacks().isEmpty()) {
							StaticBlockEntityBakeStats.fallback();
							if (plan.get().fallbacks().stream().anyMatch(fallback -> switch (fallback.reason()) {
								case MISSING_PART, INVALID_GEOMETRY, UNSUPPORTED_MODEL -> true;
								default -> false;
							})) {
								StaticBlockEntityBakeStats.failedBake();
							}
						}
						if (packMetadata || plan.isEmpty() || !appendStaticBlockEntity(layers, region, sectionPos, blockEntity, plan.get())) {
							dynamicPositions.add(pos.asLong());
						} else {
							staticPositions.add(pos.asLong());
							publications.add(StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance.fromPlan(pos.asLong(), plan.get()));
							if (hasDynamicFallback(plan.get())) {
								dynamicPositions.add(pos.asLong());
							}
						}
					}
				}
				FluidState fluidState = state.getFluidState();
				if (!fluidState.isEmpty()) {
					fluidPosition[0] = pos;
					fluidStateId[0] = Block.BLOCK_STATE_REGISTRY.getId(state);
					fluidRenderer.tesselate(region, pos, fluidOutput, state, fluidState);
				}
				if (state.getRenderShape() != RenderShape.MODEL) {
					continue;
				}
				int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
				if (packMetadata && stateId < 0) {
					throw new IllegalStateException("pack terrain metadata has no block-state ID at " + pos);
				}
				var model = access.mtlrender$blockModelSet().get(state);
				BlockQuadOutput output = useRust && RustMesher.supports(stateId) ? null : dynamicOutput(layers, stateId, packMetadata);
				BlockQuadOutput opaqueOutput = (x, y, z, quad, instance) -> {
					LayerOutput layerOutput = layers.get(ChunkSectionLayer.SOLID);
					layerOutput.writeQuad(x, y, z, quad, instance, layerOutput.javaVertices, stateId, packMetadata);
				};
				if (output != null || ModelBlockRenderer.forceOpaque(access.mtlrender$cutoutLeaves(), state) && !(useRust && RustMesher.supports(stateId))) {
					blockRenderer.tesselateBlock(
						ModelBlockRenderer.forceOpaque(access.mtlrender$cutoutLeaves(), state) ? opaqueOutput : output,
					SectionPos.sectionRelative(pos.getX()),
					SectionPos.sectionRelative(pos.getY()),
					SectionPos.sectionRelative(pos.getZ()),
					region,
					pos,
					state,
					model,
						state.getSeed(pos)
					);
				}
			}
			if (dynamicStart != 0L) {
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.JAVA_DYNAMIC_COMPILE, sectionPos.asLong(), System.nanoTime() - dynamicStart);
				dynamicStart = 0L;
			}
			assemblyStart = ChunkTiming.enabled() ? System.nanoTime() : 0L;
			if (rustMesh != null && rustMesh.limit() != 0) {
				addRustMesh(layers, rustMesh, buffers, packMetadata);
			}
			for (Map.Entry<ChunkSectionLayer, LayerOutput> entry : layers.entrySet()) {
				MeshData mesh = entry.getValue().build(buffers.buffer(entry.getKey()));
				if (mesh != null) {
					if (entry.getKey() == ChunkSectionLayer.TRANSLUCENT) {
						results.transparencyState = mesh.sortQuads(buffers.buffer(entry.getKey()), vertexSorting);
					}
					results.renderedLayers.put(entry.getKey(), mesh);
				}
			}
			results.visibilitySet = visGraph.resolve();
			Optional<StaticBlockEntityBakePublication> publication = publications.isEmpty()
				? Optional.empty()
				: Optional.of(new StaticBlockEntityBakePublication(
					sectionPos.asLong(),
					publications.getFirst().key().resourceGeneration(),
					publications,
					StaticBlockEntityBakePublication.State.CANDIDATE));
			if (packMetadata) {
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					((SectionCompilerResultsAccess) (Object) results).mtlrender$setPackTerrainMetadata(layer, layers.get(layer).packMetadata);
				}
			}
			((SectionCompilerResultsAccess) (Object) results).mtlrender$setStaticBlockEntityBakeMetadata(
				new StaticBlockEntityBakeMetadata(staticPositions, dynamicPositions, publication));
			if (assemblyStart != 0L) {
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.FINAL_ASSEMBLY, sectionPos.asLong(), System.nanoTime() - assemblyStart);
				assemblyStart = 0L;
			}
			return results;
			} finally {
				if (batchResult != null) {
					batchResult.release();
				}
			}
		} finally {
			long completedAt = ChunkTiming.enabled() ? System.nanoTime() : 0L;
			if (dynamicStart != 0L) {
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.JAVA_DYNAMIC_COMPILE, sectionPos.asLong(), completedAt - dynamicStart);
			}
			if (assemblyStart != 0L) {
				ChunkTiming.recordSectionDuration(ChunkTiming.Stage.FINAL_ASSEMBLY, sectionPos.asLong(), completedAt - assemblyStart);
			}
			BlockModelLighter.clearCache();
			if (timingStart != 0L) {
				ChunkTiming.recordSectionDuration(
					ChunkTiming.Stage.HYBRID_JAVA_COMPILE,
					sectionPos.asLong(),
					System.nanoTime() - timingStart
				);
			}
		}
	}

	private static boolean appendStaticBlockEntity(EnumMap<ChunkSectionLayer, LayerOutput> layers,
		RenderSectionRegion region, SectionPos sectionPos, BlockEntity blockEntity, StaticBlockEntityBakePlan plan) {
		if (!plan.hasStaticPortion()) {
			return false;
		}
		BlockPos origin = sectionPos.origin();
		int[] starts = new int[ChunkSectionLayer.values().length];
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			starts[layer.ordinal()] = layers.get(layer).vertexPosition();
		}
		boolean appended = false;
		for (StaticBlockEntityBakePart part : plan.staticParts()) {
			ChunkSectionLayer layer = layer(part.materialKey());
			if (layer == null) {
				continue;
			}
			try {
				if (!layers.get(layer).writeStatic(part, blockEntity.getBlockPos(), origin, region)) {
					for (ChunkSectionLayer rollbackLayer : ChunkSectionLayer.values()) {
						layers.get(rollbackLayer).truncate(starts[rollbackLayer.ordinal()]);
					}
					return false;
				}
				appended = true;
			} catch (RuntimeException ignored) {
				for (ChunkSectionLayer rollbackLayer : ChunkSectionLayer.values()) {
					layers.get(rollbackLayer).truncate(starts[rollbackLayer.ordinal()]);
				}
				return false;
			}
		}
		if (!appended) {
			for (ChunkSectionLayer rollbackLayer : ChunkSectionLayer.values()) {
				layers.get(rollbackLayer).truncate(starts[rollbackLayer.ordinal()]);
			}
		}
		return appended;
	}

	private static boolean hasDynamicFallback(StaticBlockEntityBakePlan plan) {
		return !plan.fallbacks().isEmpty();
	}

	private static Optional<StaticBlockEntityBakePlan> bakePlan(
		StaticBlockEntityBakeRegistry registry, long generation, BlockEntity blockEntity) {
		if (generation < 0L) {
			return Optional.empty();
		}
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(generation,
			BlockEntityBakeHooks.typeKey(blockEntity), BlockEntityBakeHooks.variantKey(blockEntity));
		return registry.lookup(key);
	}

	private static ChunkSectionLayer layer(String materialKey) {
		String[] fields = materialKey.split("\\u001f", -1);
		if (fields.length != 3) {
			return null;
		}
		String renderLayer = fields[1];
		if (renderLayer.contains("translucent")) {
			return ChunkSectionLayer.TRANSLUCENT;
		}
		if (renderLayer.contains("solid")) {
			return ChunkSectionLayer.SOLID;
		}
		if (renderLayer.contains("cutout")) {
			return ChunkSectionLayer.CUTOUT;
		}
		return null;
	}

	private static BlockQuadOutput dynamicOutput(EnumMap<ChunkSectionLayer, LayerOutput> layers, int stateId, boolean packMetadata) {
		return (x, y, z, quad, instance) -> {
			LayerOutput output = layers.get(quad.materialInfo().layer());
			output.writeQuad(x, y, z, quad, instance, output.javaVertices, stateId, packMetadata);
		};
	}

	private static boolean addBatchMeshes(EnumMap<ChunkSectionLayer, LayerOutput> layers, SectionCompiler.Results batch,
		boolean packMetadata) {
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : batch.renderedLayers.entrySet()) {
			MeshData mesh = entry.getValue();
			if (mesh == null || mesh.drawState().format() != DefaultVertexFormat.BLOCK
				|| mesh.drawState().primitiveTopology() != PrimitiveTopology.QUADS
				|| mesh.drawState().vertexCount() % 4 != 0
				|| (packMetadata && !(((SectionCompilerResultsAccess) (Object) batch)
					.mtlrender$packTerrainMetadata(entry.getKey()) instanceof PackTerrainJavaMesh))) {
				return false;
			}
			ByteBuffer bytes = mesh.vertexBuffer();
			if (bytes.remaining() != mesh.drawState().vertexCount() * STRIDE) {
				return false;
			}
		}
		SectionCompilerResultsAccess batchAccess = (SectionCompilerResultsAccess) (Object) batch;
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : batch.renderedLayers.entrySet()) {
			MeshData mesh = entry.getValue();
			if (mesh != null) {
				LayerOutput output = layers.get(entry.getKey());
				output.adoptRust(mesh);
				Object metadata = batchAccess.mtlrender$packTerrainMetadata(entry.getKey());
				if (metadata instanceof PackTerrainJavaMesh pack) output.packMetadata.append(pack.data());
			}
		}
		return true;
	}

	private static void addRustMesh(
		EnumMap<ChunkSectionLayer, LayerOutput> layers, ByteBuffer mesh, SectionBufferBuilderPack buffers,
		boolean packMetadata
	) {
		int first = mesh.getInt();
		boolean metadata = first == 0x4d50_4832;
		if (packMetadata && !metadata) {
			throw new IllegalStateException("Rust terrain output omitted required pack metadata");
		}
		if (packMetadata && mesh.getInt(8) != RustMesher.packRequirements().sidecarMask()) {
			throw new IllegalStateException("Rust terrain output has incompatible pack metadata mask");
		}
		int[] sizes = metadata ? new int[] {mesh.getInt(12), mesh.getInt(16), mesh.getInt(20)}
			: new int[] {first, mesh.getInt(), mesh.getInt()};
		int[] metadataSizes = metadata ? new int[] {mesh.getInt(24), mesh.getInt(28), mesh.getInt(32)} : new int[] {0, 0, 0};
		mesh.position(metadata ? 36 : 12);
		ChunkSectionLayer[] layerValues = ChunkSectionLayer.values();
		for (int index = 0; index < sizes.length; index++) {
			int size = sizes[index];
			if (size < 0 || size % STRIDE != 0 || metadataSizes[index] < 0
				|| metadataSizes[index] % PackTerrainLayout.RECORD_STRIDE != 0
				|| metadataSizes[index] / PackTerrainLayout.RECORD_STRIDE != size / STRIDE) return;
			if (size > 0 && index < layerValues.length) {
				ByteBuffer bytes = mesh.slice(mesh.position(), size);
				layers.get(layerValues[index]).writeRaw(bytes, buffers.buffer(layerValues[index]));
				mesh.position(mesh.position() + size);
			}
		}
		if (metadata) {
			for (int index = 0; index < metadataSizes.length && index < layerValues.length; index++) {
				int size = metadataSizes[index];
				if (size > 0) {
					ByteBuffer bytes = mesh.slice(mesh.position(), size);
					layers.get(layerValues[index]).packMetadata.append(bytes);
					mesh.position(mesh.position() + size);
				}
			}
		}
	}

	public static boolean matches(SectionCompiler.Results first, SectionCompiler.Results second) {
		return mismatch(first, second) == null;
	}

	@Nullable
	public static String mismatch(SectionCompiler.Results first, SectionCompiler.Results second) {
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			MeshData left = first.renderedLayers.get(layer);
			MeshData right = second.renderedLayers.get(layer);
			if (!sameQuads(left, right)) {
				return "layer " + layer;
			}
		}
		if (!sameVisibility(first.visibilitySet, second.visibilitySet)) {
			return "visibilitySet";
		}
		if (!first.blockEntities.equals(second.blockEntities)) {
			return "blockEntities";
		}
		if (!sameTransparencyState(first.transparencyState, second.transparencyState)) {
			return "transparencyState";
		}
		StaticBlockEntityBakeMetadata firstMetadata = ((SectionCompilerResultsAccess) (Object) first)
			.mtlrender$staticBlockEntityBakeMetadata();
		StaticBlockEntityBakeMetadata secondMetadata = ((SectionCompilerResultsAccess) (Object) second)
			.mtlrender$staticBlockEntityBakeMetadata();
		if (!firstMetadata.equals(secondMetadata)) {
			return "staticBlockEntityBakeMetadata";
		}
		return null;
	}

	private static boolean sameQuads(@Nullable MeshData first, @Nullable MeshData second) {
		if (first == null || second == null) {
			return first == second;
		}
		Map<ByteBuffer, Integer> left = quadCounts(first.vertexBuffer(), first.drawState().vertexCount());
		Map<ByteBuffer, Integer> right = quadCounts(second.vertexBuffer(), second.drawState().vertexCount());
		return left.equals(right);
	}

	private static boolean sameVisibility(net.minecraft.client.renderer.chunk.VisibilitySet first, net.minecraft.client.renderer.chunk.VisibilitySet second) {
		for (Direction firstDirection : Direction.values()) {
			for (Direction secondDirection : Direction.values()) {
				if (first.visibilityBetween(firstDirection, secondDirection) != second.visibilityBetween(firstDirection, secondDirection)) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean sameTransparencyState(MeshData.@Nullable SortState first, MeshData.@Nullable SortState second) {
		if (first == null || second == null) {
			return first == second;
		}
		if (first.indexType() != second.indexType() || first.centroids().size() != second.centroids().size()) {
			return false;
		}
		for (int index = 0; index < first.centroids().size(); index++) {
			if (Float.floatToIntBits(first.centroids().getX(index)) != Float.floatToIntBits(second.centroids().getX(index))
				|| Float.floatToIntBits(first.centroids().getY(index)) != Float.floatToIntBits(second.centroids().getY(index))
				|| Float.floatToIntBits(first.centroids().getZ(index)) != Float.floatToIntBits(second.centroids().getZ(index))) {
				return false;
			}
		}
		return true;
	}

	private static Map<ByteBuffer, Integer> quadCounts(ByteBuffer source, int vertices) {
		Map<ByteBuffer, Integer> counts = new HashMap<>();
		ByteBuffer bytes = source.duplicate();
		bytes.order(source.order());
		for (int vertex = 0; vertex < vertices; vertex += 4) {
			int start = vertex * STRIDE;
			int end = start + STRIDE * 4;
			ByteBuffer quad = bytes.duplicate();
			quad.position(start).limit(end);
			ByteBuffer key = quad.slice().asReadOnlyBuffer();
			counts.merge(key, 1, Integer::sum);
		}
		return counts;
	}

	private static final class LayerOutput {
		private final PackTerrainJavaMesh packMetadata;
		private final VertexBuffer javaVertices;

		private LayerOutput(int metadataMask) {
			packMetadata = new PackTerrainJavaMesh(metadataMask);
			javaVertices = new VertexBuffer(packMetadata);
		}
		private int rustBytes;

		private VertexConsumer fluidVertices(BlockPos position, int stateId, boolean packMetadata) {
			this.javaVertices.configure(position.getX() & 15, position.getY() & 15, position.getZ() & 15, stateId, packMetadata);
			return this.javaVertices;
		}

		private void writeQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance,
			VertexBuffer target, int stateId, boolean packMetadata) {
			int emission = quad.materialInfo().lightEmission();
			if (packMetadata) this.packMetadata.append(quad, x, y, z, stateId);
			for (int vertex = 0; vertex < 4; vertex++) {
				var position = quad.position(vertex);
				long packed = quad.packedUV(vertex);
				target.vertex(
					position.x() + x,
					position.y() + y,
					position.z() + z,
					instance.getColor(vertex),
					UVPair.unpackU(packed),
					UVPair.unpackV(packed),
					instance.getLightCoordsWithEmission(vertex, emission)
				);
			}
		}

		private int vertexPosition() {
			return javaVertices.position();
		}

		private void truncate(int position) {
			javaVertices.truncate(position);
		}

		private boolean writeStatic(StaticBlockEntityBakePart part, BlockPos blockEntityPos,
			BlockPos sectionOrigin, RenderSectionRegion region) {
			ByteBuffer payload = ByteBuffer.wrap(part.geometryPayload()).order(ByteOrder.LITTLE_ENDIAN);
			if (payload.remaining() < Integer.BYTES) {
				return false;
			}
			int vertices = payload.getInt();
			if (vertices <= 0 || vertices % 4 != 0 || vertices > payload.remaining() / 32
				|| payload.remaining() != vertices * 32) {
				return false;
			}
			ByteBuffer checked = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			for (int vertex = 0; vertex < vertices; vertex++) {
				float x = checked.getFloat();
				float y = checked.getFloat();
				float z = checked.getFloat();
				float u = checked.getFloat();
				float v = checked.getFloat();
				checked.position(checked.position() + 12);
				if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
					|| !Float.isFinite(u) || !Float.isFinite(v)) {
					return false;
				}
			}
			int light = (region.getBrightness(LightLayer.SKY, blockEntityPos) << 16)
				| region.getBrightness(LightLayer.BLOCK, blockEntityPos);
			for (int vertex = 0; vertex < vertices; vertex++) {
				float x = payload.getFloat();
				float y = payload.getFloat();
				float z = payload.getFloat();
				float u = payload.getFloat();
				float v = payload.getFloat();
				payload.position(payload.position() + 12);
				javaVertices.vertex(
					blockEntityPos.getX() - sectionOrigin.getX() + x,
					blockEntityPos.getY() - sectionOrigin.getY() + y,
					blockEntityPos.getZ() - sectionOrigin.getZ() + z,
					0xffff_ffff, u, v, light);
			}
			return true;
		}

		private MeshData build(ByteBufferBuilder target) {
			javaVertices.data.flip();
			int javaSize = javaVertices.data.limit();
			if (rustBytes == 0 && javaSize == 0) {
				return null;
			}
			if (javaSize > 0) {
				writeJava(javaVertices.data, target);
			}
			int rustSize = rustBytes;
			if ((Boolean.getBoolean("mtlrender.debug.mesherCheck")
				|| Boolean.getBoolean("mtlrender.debug.mesherCopyCheck"))
				&& (rustSize % STRIDE != 0 || javaSize % STRIDE != 0)) {
				throw new IllegalStateException("invalid Rust or Java vertex byte count");
			}
			ByteBufferBuilder.Result vertexBuffer = target.build();
			if (vertexBuffer == null) {
				return null;
			}
			int vertices = (rustSize + javaSize) / STRIDE;
			return new MeshData(
				vertexBuffer,
				new MeshData.DrawState(DefaultVertexFormat.BLOCK, vertices, vertices / 4 * 6, PrimitiveTopology.QUADS, IndexType.least(vertices))
			);
		}

		private void writeRaw(ByteBuffer source, ByteBufferBuilder target) {
			ByteBuffer input = source.duplicate();
			int size = input.remaining();
			long address = target.reserve(size);
			ByteBuffer output = MemorySegment.ofAddress(address).reinterpret(size).asByteBuffer().order(ByteOrder.nativeOrder());
			output.put(input);
			rustBytes = Math.addExact(rustBytes, size);
		}

		private void adoptRust(MeshData mesh) {
			rustBytes = Math.addExact(rustBytes, mesh.vertexBuffer().remaining());
			mesh.close();
		}

		private void writeJava(ByteBuffer source, ByteBufferBuilder target) {
			ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
			int size = input.limit();
			long address = target.reserve(size);
			ByteBuffer output = MemorySegment.ofAddress(address).reinterpret(size).asByteBuffer().order(ByteOrder.nativeOrder());
			for (int offset = 0; offset < size; offset += STRIDE) {
				for (int position = 0; position < 12; position += 4) {
					output.putFloat(offset + position, input.getFloat(offset + position));
				}
				output.putInt(offset + 12, toAbgr(input.getInt(offset + 12)));
				output.putFloat(offset + 16, input.getFloat(offset + 16));
				output.putFloat(offset + 20, input.getFloat(offset + 20));
				output.putInt(offset + 24, input.getInt(offset + 24));
			}
		}
	}

	private static final class VertexBuffer implements VertexConsumer {
		private ByteBuffer data = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
		private final PackTerrainJavaMesh packMetadata;
		private float blockX;
		private float blockY;
		private float blockZ;
		private int stateId;
		private boolean metadataEnabled;
		private final float[] positionsX = new float[4];
		private final float[] positionsY = new float[4];
		private final float[] positionsZ = new float[4];
		private final float[] uvsU = new float[4];
		private final float[] uvsV = new float[4];
		private int vertexCount;

		private VertexBuffer() { this(null, 0.0F, 0.0F, 0.0F, 0, false); }
		private VertexBuffer(PackTerrainJavaMesh packMetadata) { this(packMetadata, 0.0F, 0.0F, 0.0F, 0, false); }
		private VertexBuffer(PackTerrainJavaMesh packMetadata, float blockX, float blockY, float blockZ, int stateId, boolean metadataEnabled) {
			this.packMetadata = packMetadata;
			configure(blockX, blockY, blockZ, stateId, metadataEnabled);
		}

		private void configure(float blockX, float blockY, float blockZ, int stateId, boolean metadataEnabled) {
			this.blockX = blockX;
			this.blockY = blockY;
			this.blockZ = blockZ;
			this.stateId = stateId;
			this.metadataEnabled = metadataEnabled;
		}
		private float x;
		private float y;
		private float z;
		private int color;
		private float u;
		private float v;
		private int light;

		private int position() {
			return data.position();
		}

		private void truncate(int position) {
			data.position(position);
		}

		private void vertex(float x, float y, float z, int color, float u, float v, int light) {
			ensure(STRIDE);
			data.putFloat(x).putFloat(y).putFloat(z).putInt(color).putFloat(u).putFloat(v).putInt(light);
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			if (metadataEnabled && vertexCount < 4) {
				positionsX[vertexCount] = x; positionsY[vertexCount] = y; positionsZ[vertexCount] = z;
			}
			this.x = x;
			this.y = y;
			this.z = z;
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			this.color = color;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return setColor((a << 24) | (r << 16) | (g << 8) | b);
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.u = u;
			this.v = v;
			if (metadataEnabled && vertexCount < 4) { uvsU[vertexCount] = u; uvsV[vertexCount] = v; }
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			this.light = (v << 16) | (u & 0xffff);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			vertex(this.x, this.y, this.z, color, u, v, light);
			if (metadataEnabled && ++vertexCount == 4) {
				packMetadata.append(positionsX, positionsY, positionsZ, uvsU, uvsV, blockX, blockY, blockZ, stateId);
				vertexCount = 0;
			}
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}

		private void ensure(int bytes) {
			if (data.remaining() >= bytes) {
				return;
			}
			int required = data.position() + bytes;
			int capacity = data.capacity();
			while (capacity < required) {
				capacity = Math.multiplyExact(capacity, 2);
			}
			ByteBuffer replacement = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
			data.flip();
			replacement.put(data);
			data = replacement;
		}
	}

	private static int toAbgr(int argb) {
		return (argb & 0xff00_ff00) | ((argb & 0x00ff_0000) >>> 16) | ((argb & 0x0000_00ff) << 16);
	}
}
