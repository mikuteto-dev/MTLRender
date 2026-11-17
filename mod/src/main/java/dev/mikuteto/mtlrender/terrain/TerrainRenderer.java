package dev.mikuteto.mtlrender.terrain;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakePublication;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import dev.mikuteto.mtlrender.debug.MeshCheck;
import dev.mikuteto.mtlrender.entity.EntityFeatureRenderer;
import dev.mikuteto.mtlrender.culling.FeatureInstanceStream;
import dev.mikuteto.mtlrender.lod.LodRenderPath;
import dev.mikuteto.mtlrender.lod.LodSaveManager;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.dh.DhDepthResources;
import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;
import dev.mikuteto.mtlrender.pack.dh.DhRenderContract;
import dev.mikuteto.mtlrender.pack.dh.DhShadowFrame;
import dev.mikuteto.mtlrender.pack.shadow.ShadowPassPlan;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.pathtracing.PathTracer;
import dev.mikuteto.mtlrender.pathtracing.VoxelBricks;
import dev.mikuteto.mtlrender.tune.TerrainDrawPath;
import dev.mikuteto.mtlrender.tune.TuneProfile;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

public final class TerrainRenderer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final TerrainRenderer INSTANCE = new TerrainRenderer();
	private static final long UNIT_ALIGNMENT = 256;
	private static final int INDEX_NONE = 0;
	private static final int INDEX_SHORT = 1;
	private static final int INDEX_INT = 2;
	private static final double RING_ONE_DISTANCE_SQUARED = 128.0 * 128.0;
	private static final double RING_TWO_DISTANCE_SQUARED = 256.0 * 256.0;
	private static final double RING_THREE_DISTANCE_SQUARED = 384.0 * 384.0;

	private final Config.Terrain terrain = Config.get().terrain;
	private final TuneProfile profile = TuneProfile.resolve(terrain);
	private final boolean enabled = terrain.enabled;
	private final boolean faceCulling = terrain.faceCulling;
	private final TerrainDrawPath drawPath = resolveDrawPath(profile);
	private final TerrainDrawPath rasterPath = drawPath == TerrainDrawPath.MESH
		? (profile.indexed() ? TerrainDrawPath.INDEXED : TerrainDrawPath.NON_INDEXED) : drawPath;
	private final boolean indexed = rasterPath == TerrainDrawPath.INDEXED;
	private final boolean compactVertices = profile.compactVertices();
	private final OcclusionMode requestedMode = OcclusionMode.fromString(terrain.occlusion);
	private final boolean automaticOcclusion = requestedMode == OcclusionMode.AUTO;
	private OcclusionMode frameOcclusionMode = initialOcclusionMode();
	private OcclusionMode effectiveFrameMode = OcclusionMode.OFF;
	private final boolean halfVaryings = terrain.halfVaryings;
	private final boolean expand = terrain.expand;
	private final boolean lodEnabled = terrain.lod;
	private final boolean lodDropPlants = terrain.lodDropPlants;
	private final int lodMinRing = profile.lodMinRing();
	private final PathTracer pathTracer = PathTracer.get();
	private final LodRenderPath lodRenderPath = new LodRenderPath();
	private final boolean translucentSort = terrain.translucentSort;
	private final OitMode translucentOit = OitMode.resolve(terrain.translucentOit);
	private final boolean sortCheck = Boolean.getBoolean("mtlrender.debug.sortCheck");
	private final boolean expandCheck = Boolean.getBoolean("mtlrender.debug.expandCheck");
	private final boolean quadStatsEnabled = Boolean.getBoolean("mtlrender.bench.quadStats");
	private final boolean packDiagnostics = Boolean.getBoolean("mtlrender.debug.packTerrain");
	private long packMetadataRecords;
	private long packMetadataAddresses;
	private long packMetadataFirstAddress;
	private long packMetadataLastAddress;
	private long packBlockIds;
	private long packTangents;
	private String packSolidProgram = "not_run";
	private String packCutoutProgram = "not_run";
	private String packWaterProgram = "not_run";
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f clipMatrix = new Matrix4f();
	private final Matrix4f modelViewCopy = new Matrix4f();
	private final float[] clipValues = new float[16];
	private final float[] previousClipValues = new float[16];
	private final float[] framePreviousClip = new float[16];
	private boolean clipHistoryValid;
	private final int[] emptyFaces = new int[TerrainPreparation.FACE_COUNT];
	private final EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> emptyDraws = emptyDraws();
	private final GpuBufferSlice[] sectionInfoArray = new GpuBufferSlice[1];
	private final TerrainPipelines half = newPipelines(true);
	private final TerrainPipelines full = newPipelines(false);
	private final TerrainPipelines prepass = new TerrainPipelines(indexed, compactVertices, true, false, true, false);
	private PackTerrainRenderState packState = PackTerrainRenderState.none();
	private PackTerrainRenderState previousPackState = PackTerrainRenderState.none();
	private PackRuntimeService packRuntime;
	private PackTerrainPipelines packPipelines;
	private final UnitWriter writer = new UnitWriter();
	private UnitWriter packWriter;
	private RecordWriter records = new RecordWriter();
	private final VisibleWriter visibleWriter = new VisibleWriter();
	private final TerrainRecordPreparer recordPreparer = new TerrainRecordPreparer();
	private final ExecutorService preparationExecutor = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "MTLRender terrain preparation");
		thread.setDaemon(true);
		return thread;
	});
	private Future<TerrainUnitPreparer.Prepared> unitPreparation;
	private Future<TerrainRecordPreparer.Result> recordPreparation;
	private long preparationGeneration;
	private TerrainPreparation.Frame preparedFrame;
	private TerrainPreparation.Frame preparedRecordFrame;
	private TerrainPreparation.Frame recordTaskFrame;
	private TerrainUnitPreparer.Prepared preparedUnits;
	private TerrainRecordPreparer.Result preparedRecords;
	private TerrainRecordPreparer.CacheState recordState = new TerrainRecordPreparer.CacheState(0, 0);
	private final SortWriter sortWriter = new SortWriter();
	private final CullTuner tuner = new CullTuner(profile.cullingInterval(), initialOcclusionMode(), automaticOcclusion);
	private long lastPrepareNanos;
	private boolean cullFrame;
	private int cullFrameCount;
	private int frames;
	private final int[] firstUnit = new int[ChunkSectionLayer.values().length];
	private final int[] unitCount = new int[ChunkSectionLayer.values().length];
	private final int[] recordLayerUnits = new int[ChunkSectionLayer.values().length];
	private final long[] quadStats = new long[4 * 3 * 3];
	private final long[] mergeStats = new long[4 * 3 * 3];
	private volatile boolean failed;
	private int lastUnits;
	private long lastQuads;
	private int lastSections;
	private int unitSections;
	private int[] cachedLayerUnits = new int[0];
	private int[] cachedUnits = new int[0];
	private boolean[] dirty = new boolean[0];
	private boolean[] cachedLod = new boolean[0];
	private boolean allDirty = true;
	private int recordSections;
	private int layerMask;
	private int expandCapacity;
	private long expandUnitsTotal;
	private long expandQuadsTotal;
	private float[] clip;
	private int cameraX;
	private int cameraY;
	private int cameraZ;
	private float offsetX;
	private float offsetY;
	private float offsetZ;
	private GpuBufferSlice phaseB;
	private ChunkSectionLayerGroup phaseBGroup;
	private GpuBufferSlice singlePhaseOutput;
	private long singlePhaseUnitsBytes;
	private ChunkSectionLayerGroup singlePhaseGroup;
	private ChunkSectionsToRender prepared;
	private GpuBufferSlice units;
	private GpuBufferSlice expanded;
	private long expandedUnitsBytes;
	private boolean expandFrame;
	private long frameMillis;
	private GpuBufferSlice sectionInfo;
	private GpuBuffer quadIndices;
	private long sortMismatches;
	private final boolean chunkTiming = ChunkTiming.enabled();
	private final LongArrayList firstDrawnPending = chunkTiming ? new LongArrayList() : null;
	private final LongOpenHashSet firstDrawnQueued = chunkTiming ? new LongOpenHashSet() : null;
	private final LongOpenHashSet firstDrawnMarked = chunkTiming ? new LongOpenHashSet() : null;
	private long timingRunStart;
	private boolean terrainDrawIssued;
	private boolean meshReady;
	private boolean featureRequestIssued;
	private boolean featureHistoryValid;
	private record PackMetadata(long address, int stride, long generation, long requirements) {}
	private EnumSet<DhProgramKind> dhPrograms = EnumSet.noneOf(DhProgramKind.class);
	private GpuTextureView featureDepth;
	private float[] featurePreviousClip;
	private FeatureMask featureMask;
	private ShadowCullView shadowCullView;

	private static EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> emptyDraws() {
		EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> result = new EnumMap<>(ChunkSectionLayer.class);
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			result.put(layer, new Int2ObjectOpenHashMap<>());
		}
		return result;
	}

	private TerrainRenderer() {
		LOGGER.info(
			"MTLRender terrain paths: path={}, requestedOcclusion={}, selectedOcclusion={}, cullingInterval={}, lodMinRing={}, compactVertices={}",
			selectedPath(), requestedOcclusionMode(), selectedOcclusionMode(), profile.cullingInterval(), profile.lodMinRing(), profile.compactVertices()
		);
	}

	private static TerrainDrawPath resolveDrawPath(TuneProfile profile) {
		String override = System.getProperty("mtlrender.terrainPath");
		return override == null || override.isBlank() ? profile.drawPath() : TerrainDrawPath.fromConfig(override, profile.drawPath());
	}

	private TerrainDrawPath selectedPath() {
		if (MetalAccess.meshCheckEnabled()) {
			return meshCheckVariant() == MeshCheck.Variant.MESH ? TerrainDrawPath.MESH : rasterPath;
		}
		return System.getProperty("mtlrender.terrainPath") == null && AbSwitch.is("meshShader")
			? TerrainDrawPath.MESH : drawPath;
	}

	private static MeshCheck.Variant meshCheckVariant() {
		MeshCheck.Variant forced = MeshCheck.forcedVariant();
		if (forced != null) {
			return forced;
		}
		if ("meshCheck".equals(AbSwitch.NAME)) {
			return AbSwitch.b() ? MeshCheck.Variant.MESH : MeshCheck.Variant.RASTER;
		}
		return "mesh".equalsIgnoreCase(System.getProperty("mtlrender.debug.meshCheck.variant", "raster"))
			? MeshCheck.Variant.MESH : MeshCheck.Variant.RASTER;
	}

	private TerrainPipelines newPipelines(boolean half) {
		return new TerrainPipelines(
			indexed,
			compactVertices,
			Boolean.getBoolean("mtlrender.cutoutPrepass"),
			Boolean.getBoolean("mtlrender.cutoutPrepassDiscard"),
			half,
			translucentOit.enabled()
		);
	}

	public static TerrainRenderer get() {
		return INSTANCE;
	}

	public boolean active() {
		return enabled && !failed && MetalAccess.active();
	}

	boolean compactVertices() {
		return compactVertices && active();
	}

	boolean lodEnabled() {
		return lodEnabled;
	}

	public boolean translucentSortManaged() {
		return active() && translucentSort;
	}

	public boolean translucentSortCompile() {
		return translucentSortManaged();
	}

	public String packDiagnosticsSummary() {
		if (!packDiagnostics) return "";
		return String.format(java.util.Locale.ROOT,
			"pack_terrain_active=%b%npack_terrain_solid_program=%s%npack_terrain_cutout_program=%s%npack_terrain_water_program=%s%npack_terrain_metadata_records=%d%npack_terrain_metadata_nonzero_addresses=%d%npack_terrain_metadata_first_address=%d%npack_terrain_metadata_last_address=%d%npack_terrain_block_ids_nonzero=%d%npack_terrain_tangents_nonzero=%d%npack_terrain_requirements_fingerprint=%d%n",
			packState.packActive(), packSolidProgram, packCutoutProgram, packWaterProgram, packMetadataRecords,
			packMetadataAddresses, packMetadataFirstAddress, packMetadataLastAddress, packBlockIds, packTangents,
			packState.packActive() ? packState.requirementsFingerprint() : 0);
	}

	boolean gpuTranslucentSort() {
		return translucentSortManaged() && (AbSwitch.NAME.isEmpty() || AbSwitch.is("translucentSort"));
	}

	public MeshData.SortState identityTranslucentSort(MeshData mesh, ByteBufferBuilder target, com.mojang.blaze3d.vertex.VertexSorting sorting) {
		return TranslucentSort.identity(mesh, target, sorting);
	}

	boolean lodBuild() {
		return lodEnabled && (AbSwitch.NAME.isEmpty() || "lod".equals(AbSwitch.NAME));
	}

	private boolean lodActive() {
		return AbSwitch.NAME.isEmpty() ? lodEnabled : AbSwitch.is("lod");
	}

	private static int quadCount(int[] faces) {
		int total = 0;
		for (int face : faces) {
			total += face;
		}
		return total;
	}

	public boolean owns(ChunkSectionsToRender toRender) {
		return toRender == prepared;
	}

	public int lastUnits() {
		return lastUnits;
	}

	public long lastQuads() {
		return lastQuads;
	}

	public int lastSections() {
		return lastSections;
	}

	public long[] quadStats() {
		return quadStats;
	}

	public long[] mergeStats() {
		return mergeStats;
	}

	public int cullFrames() {
		return cullFrameCount;
	}

	public MeshCheck.VariantState meshCheckState() {
		return new MeshCheck.VariantState(
			meshCheckVariant(),
			new MeshCheck.LayerCounts(recordLayerUnits[0], recordLayerUnits[1], recordLayerUnits[2])
		);
	}

	public int frames() {
		return frames;
	}

	public void setProjection(Matrix4fc matrix) {
		projection.set(matrix);
	}

	public void markDirty(int index) {
		if (index >= 0 && index < dirty.length) {
			dirty[index] = true;
		}
		recordState = recordState.markDirty(index);
	}

	public void markAllDirty() {
		if (!packState.packActive()) {
			return;
		}
		preparedRecords = null;
		preparedRecordFrame = null;
		preparedUnits = null;
		preparedFrame = null;
		java.util.Arrays.fill(dirty, true);
		allDirty = true;
		recordState = recordState.markAllDirty();
		invalidateFeatureHistory();
	}

	private void invalidateFeatureHistory() {
		this.featureMask = null;
		this.featureDepth = null;
		this.featurePreviousClip = null;
		this.featureHistoryValid = false;
	}

	private OcclusionMode initialOcclusionMode() {
		return requestedMode == OcclusionMode.AUTO ? profile.occlusionMode() : requestedMode;
	}

	private static boolean gpuMode(OcclusionMode mode) {
		return mode.usesGpuCulling();
	}

	private OcclusionMode gpuAbBaseline(OcclusionMode mode) {
		if (gpuMode(mode)) {
			return mode;
		}
		return MetalAccess.cullingSupported() ? OcclusionMode.TWO_PHASE_HIZ : OcclusionMode.CPU_BFS;
	}

	private OcclusionMode selectFrameMode(OcclusionMode mode) {
		if ("occlusion-single".equals(AbSwitch.NAME)) {
			return AbSwitch.b() ? OcclusionMode.SINGLE_PHASE_HIZ : gpuAbBaseline(mode);
		}
		if ("occlusion-cpu".equals(AbSwitch.NAME)) {
			return AbSwitch.b() ? OcclusionMode.CPU_BFS : gpuAbBaseline(mode);
		}
		if ("occlusion-off".equals(AbSwitch.NAME)) {
			return AbSwitch.b() ? OcclusionMode.OFF : gpuAbBaseline(mode);
		}
		return mode;
	}

	private boolean cpuBfs() {
		return selectedOcclusionMode() == OcclusionMode.CPU_BFS;
	}

	private boolean cpuVisibleSections() {
		return selectedOcclusionMode().usesCpuVisibleSections();
	}

	public OcclusionMode requestedOcclusionMode() {
		return requestedMode;
	}

	public OcclusionMode selectedOcclusionMode() {
		return frameOcclusionMode;
	}

	public OcclusionMode effectiveOcclusionMode() {
		return effectiveFrameMode;
	}

	public String occlusionAb() {
		return switch (AbSwitch.NAME) {
			case "occlusion-single", "occlusion-cpu", "occlusion-off" -> AbSwitch.NAME;
			default -> "";
		};
	}

	public String occlusionAbVariant() {
		return occlusionAb().isEmpty() ? "none" : AbSwitch.b() ? "B" : "A";
	}

	private boolean gpuOcclusion() {
		return gpuMode(selectedOcclusionMode());
	}

	private List<SectionRenderDispatcher.RenderSection> visibleSections(LevelRenderer renderer) {
		List<SectionRenderDispatcher.RenderSection> visible = renderer.visibleSections();
		if (!cpuVisibleSections()) {
			return visible;
		}
		return cpuBfs() ? CpuBfsVisibility.snapshot(visible) : List.copyOf(visible);
	}

	public static ShadowCullView shadowCullView(ShadowPassPlan plan, LightShadowFrustum frustum) {
		java.util.Objects.requireNonNull(plan, "shadow plan");
		java.util.Objects.requireNonNull(frustum, "shadow frustum");
		if (!plan.shadowTerrain()) return null;
		return new ShadowCullView(plan, frustum.clipValues(), frustum.cameraBlockX(), frustum.cameraBlockY(),
			frustum.cameraBlockZ(), frustum.cameraOffsetX(), frustum.cameraOffsetY(), frustum.cameraOffsetZ());
	}

	public ShadowCullView prepareShadow(ShadowPassPlan plan, LightShadowFrustum frustum) {
		ShadowCullView view = shadowCullView(plan, frustum);
		shadowCullView = view;
		return view;
	}

	public void endShadow() {
		shadowCullView = null;
	}

	public ChunkSectionsToRender prepare(LevelRenderer renderer, Matrix4fc modelView) {
		shadowCullView = null;
		phaseB = null;
		phaseBGroup = null;
		singlePhaseOutput = null;
		singlePhaseUnitsBytes = 0L;
		singlePhaseGroup = null;
		effectiveFrameMode = OcclusionMode.OFF;
		cullFrame = false;
		Minecraft minecraft = Minecraft.getInstance();
		if (lodEnabled && minecraft.level != null) {
			LodSaveManager.get().update(minecraft.level);
		}
		if (chunkTiming) {
			long runStart = ChunkTiming.runStartNanos();
			if (runStart != timingRunStart) {
				timingRunStart = runStart;
				firstDrawnPending.clear();
				firstDrawnQueued.clear();
				firstDrawnMarked.clear();
			}
			terrainDrawIssued = false;
		}
		this.featureRequestIssued = false;
		packState = snapshotPackState(minecraft);
		if (!packState.equals(previousPackState)) {
			markAllDirty();
			previousPackState = packState;
		}
		dhPrograms = EnumSet.noneOf(DhProgramKind.class);
		if (!active() || !pipelinesReady()) {
			prepared = null;
			invalidateFeatureHistory();
			return null;
		}
		long nowNanos = System.nanoTime();
		refreshDhPrograms(minecraft);
		RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
		tuner.resolution(mainTarget.width, mainTarget.height);
		long gpuBusy = MetalAccess.statTotal(MetalAccess.GPU_BUSY);
		OcclusionMode automaticMode = automaticOcclusion
			? tuner.next(lastPrepareNanos == 0 ? 0 : nowNanos - lastPrepareNanos, gpuBusy)
			: requestedMode;
		frameOcclusionMode = selectFrameMode(automaticMode);
		if (gpuMode(frameOcclusionMode) && !MetalAccess.cullingSupported()) {
			frameOcclusionMode = OcclusionMode.CPU_BFS;
		}
		lastPrepareNanos = nowNanos;
		cullFrame = gpuOcclusion();
		if (AbSwitch.is("forceCull") && gpuOcclusion() && MetalAccess.cullingSupported()) {
			cullFrame = true;
		}
		if (packCpuUnits()) {
			cullFrame = false;
		}
		effectiveFrameMode = frameOcclusionMode;
		frames++;
		if (cullFrame) {
			cullFrameCount++;
		}
		expandFrame = expand && indexed && !AbSwitch.is("cpuUnits");
		frameMillis = Util.getMillis();
		GpuTextureView atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		Vec3 cameraPos = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		cameraX = Mth.floor(cameraPos.x);
		cameraY = Mth.floor(cameraPos.y);
		cameraZ = Mth.floor(cameraPos.z);
		offsetX = (float) (cameraX - cameraPos.x);
		offsetY = (float) (cameraY - cameraPos.y);
		offsetZ = (float) (cameraZ - cameraPos.z);
		GpuDevice device = RenderSystem.getDevice();
		if (indexed && quadIndices == null) {
			quadIndices = createQuadIndices(device);
		}
		long currentSortMismatches = MetalAccess.statTotal(MetalAccess.SORT_MISMATCHES);
		if (currentSortMismatches > sortMismatches) {
			LOGGER.warn("Translucent GPU sort mismatches: {}", currentSortMismatches - sortMismatches);
		}
		sortMismatches = currentSortMismatches;
		TerrainPreparation.Frame currentFrame = snapshotFrame(renderer, cameraPos, ++preparationGeneration);
		if (expandFrame) {
			pollRecordPreparation();
			long unitsTotal = MetalAccess.statTotal(MetalAccess.EXPAND_UNITS);
			long quadsTotal = MetalAccess.statTotal(MetalAccess.EXPAND_QUADS);
			lastUnits = (int) (unitsTotal - expandUnitsTotal);
			lastQuads = quadsTotal - expandQuadsTotal;
			lastSections = currentFrame.sections().size();
			expandUnitsTotal = unitsTotal;
			expandQuadsTotal = quadsTotal;
			boolean recordReady = preparedRecords != null && preparedRecordFrame != null && sameSnapshot(preparedRecordFrame, currentFrame);
			if (!recordReady) {
				expanded = null;
				clearUnitCounts();
			} else {
				ByteBuffer updatesBytes = ByteBuffer.wrap(preparedRecords.updates()).order(ByteOrder.nativeOrder());
				ByteBuffer visibleBytes = ByteBuffer.wrap(preparedRecords.visible()).order(ByteOrder.nativeOrder());
				int updateCount = preparedRecords.batch().updates().size();
				int visibleCount = preparedRecords.batch().visible().size();
				GpuBufferSlice updates = updateCount == 0 ? null : device.createCommandEncoder().transientMemory()
					.uploadGpu(updatesBytes, UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
				GpuBufferSlice visible = visibleCount == 0 ? null : device.createCommandEncoder().transientMemory()
					.uploadGpu(visibleBytes, UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
				expandCapacity = preparedRecords.batch().expandCapacity();
				recordSections = preparedRecords.batch().sectionCount();
				unitSections = recordSections;
				System.arraycopy(preparedRecords.layerUnits(), 0, recordLayerUnits, 0, recordLayerUnits.length);
				layerMask = preparedRecords.batch().layerMask();
				if (updateCount == 0 && visibleCount == 0) {
					expanded = null;
				} else {
					expandedUnitsBytes = Mth.roundToward((long) expandCapacity * PackTerrainUnits.unitBytes(packCpuUnits()), UNIT_ALIGNMENT);
					expanded = allocateOutput(expandedUnitsBytes + 64);
					try {
						MetalAccess.expandTerrain(updates, updateCount, visible, visibleCount, recordSections, preparedRecords.batch().cullMask(), layerMask,
							AbSwitch.is("skipPlants") ? 1 : preparedRecords.batch().options(), TerrainShaderPatch.VERTICES_PER_UNIT, expandCapacity,
							cameraX, cameraY, cameraZ, offsetX, offsetY, offsetZ, expanded, expanded.offset() + expandedUnitsBytes);
					} catch (RuntimeException exception) {
						LOGGER.warn("Terrain expansion failed; using CPU-visible terrain", exception);
						expanded = null;
					}
				}
			}
			boolean cpuFallback = expanded == null && !currentFrame.sections().isEmpty();
			if (cpuFallback) {
				if (packCpuUnits()) {
					prepareCollectedCpuUnits(renderer, device);
				} else {
					prepareCpuUnits(currentFrame, device);
				}
			}
			if (unitPreparation == null && (expandCheck || quadStatsEnabled) && !cpuFallback) {
				preparedFrame = currentFrame;
				unitPreparation = preparationExecutor.submit(() -> TerrainUnitPreparer.prepare(currentFrame));
			}
		} else {
			if (packCpuUnits()) {
				prepareCollectedCpuUnits(renderer, device);
			} else {
				pollUnitPreparation();
			}
			boolean unitReady = !packCpuUnits() && preparedUnits != null && preparedFrame != null && sameSnapshot(preparedFrame, currentFrame);
			if (unitReady) {
				UnitWriter.Result result = preparedUnits.units();
				units = result.count() == 0 ? null : device.createCommandEncoder().transientMemory()
					.uploadGpu(result.bytes(), UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
				int[] first = preparedUnits.batch().first();
				int[] counts = preparedUnits.batch().counts();
				System.arraycopy(first, 0, firstUnit, 0, firstUnit.length);
				System.arraycopy(counts, 0, unitCount, 0, unitCount.length);
				lastUnits = result.count();
				lastQuads = result.quads();
				lastSections = preparedUnits.diagnostics().sections().size();
			} else if (!packCpuUnits() && cpuVisibleSections()) {
				prepareCpuUnits(currentFrame, device);
			} else if (!packCpuUnits()) {
				units = null;
				clearUnitCounts();
			}
			if (!packCpuUnits() && unitPreparation == null && !cpuVisibleSections()) {
				preparedFrame = currentFrame;
				unitPreparation = preparationExecutor.submit(() -> TerrainUnitPreparer.prepare(currentFrame));
			}
		}
		if (expandFrame && recordPreparation == null) {
			recordTaskFrame = currentFrame;
			TerrainRecordPreparer.CacheState state = recordState;
			recordPreparation = preparationExecutor.submit(() -> recordPreparer.prepare(currentFrame, state));
		}
		if (expandFrame && (expandCheck || quadStatsEnabled)) {
			pollUnitPreparation();
			if (preparedUnits != null && preparedFrame != null && sameSnapshot(preparedFrame, currentFrame)) {
				if (expandCheck && expanded != null) {
					UnitWriter.Result reference = preparedUnits.units();
					GpuBufferSlice cpu = reference.count() == 0 ? expanded : device.createCommandEncoder().transientMemory()
						.uploadGpu(reference.bytes(), UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
					MetalAccess.checkExpand(cpu, reference.count(), preparedUnits.batch().first()[0], preparedUnits.batch().first()[1], preparedUnits.batch().first()[2],
						expanded, expandedUnitsBytes, expanded.offset() + expandedUnitsBytes);
				}
			}
		}
		clipMatrix.set(projection).mul(modelView);
		clipMatrix.get(clipValues);
		if (clipHistoryValid) {
			System.arraycopy(previousClipValues, 0, framePreviousClip, 0, framePreviousClip.length);
		} else {
			System.arraycopy(clipValues, 0, framePreviousClip, 0, framePreviousClip.length);
		}
		System.arraycopy(clipValues, 0, previousClipValues, 0, previousClipValues.length);
		clipHistoryValid = true;
		clip = clipValues;
		pathTracer.prepare(cameraPos, clipMatrix);
		if (terrain.lodBeyondRenderDistance) {
			lodRenderPath.prepare(minecraft.level, cameraPos, clip, minecraft.options.renderDistance().get());
		}
		sortTranslucent(renderer, cameraPos, device);
		modelViewCopy.set(modelView);
		sectionInfo = RenderSystem.getDynamicUniforms().writeChunkSections(
			new DynamicUniforms.ChunkSectionInfo(modelViewCopy, 0, 0, 0, 1.0F, atlas.getWidth(0), atlas.getHeight(0))
		)[0];
		sectionInfoArray[0] = sectionInfo;
		prepared = new ChunkSectionsToRender(atlas, emptyDraws, 0, sectionInfoArray);
		return prepared;
	}

	private void pollRecordPreparation() {
		Future<TerrainRecordPreparer.Result> task = recordPreparation;
		if (task == null || !task.isDone()) {
			return;
		}
		recordPreparation = null;
		try {
			TerrainRecordPreparer.Result result = task.get();
			if (recordTaskFrame != null) {
				recordState = result.apply(recordState);
				preparedRecords = result;
				preparedRecordFrame = recordTaskFrame;
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException exception) {
			LOGGER.warn("Terrain record preparation failed; retaining the previous batch", exception.getCause());
		}
	}

	private void pollUnitPreparation() {
		Future<TerrainUnitPreparer.Prepared> task = unitPreparation;
		if (task == null || !task.isDone()) {
			return;
		}
		unitPreparation = null;
		try {
			TerrainUnitPreparer.Prepared result = task.get();
			if (preparedFrame != null && result.batch().isCurrent(preparedFrame)) {
				preparedUnits = result;
				applyDiagnostics(result.diagnostics());
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException exception) {
			LOGGER.warn("Terrain worker preparation failed; retaining the previous batch", exception.getCause());
		}
	}

	private void clearUnitCounts() {
		java.util.Arrays.fill(firstUnit, 0);
		java.util.Arrays.fill(unitCount, 0);
		java.util.Arrays.fill(recordLayerUnits, 0);
		lastUnits = 0;
		lastQuads = 0;
		lastSections = 0;
		unitSections = 0;
	}

	private void prepareCpuUnits(TerrainPreparation.Frame frame, GpuDevice device) {
		TerrainUnitPreparer.Prepared result = TerrainUnitPreparer.prepare(frame);
		preparedUnits = result;
		preparedFrame = frame;
		applyDiagnostics(result.diagnostics());
		UnitWriter.Result unitsResult = result.units();
		units = unitsResult.count() == 0 ? null : device.createCommandEncoder().transientMemory()
			.uploadGpu(unitsResult.bytes(), UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
		int[] first = result.batch().first();
		int[] counts = result.batch().counts();
		System.arraycopy(first, 0, firstUnit, 0, firstUnit.length);
		System.arraycopy(counts, 0, unitCount, 0, unitCount.length);
		lastUnits = unitsResult.count();
		lastQuads = unitsResult.quads();
		lastSections = result.diagnostics().sections().size();
		unitSections = result.diagnostics().sections().size();
	}

	private void applyDiagnostics(TerrainPreparation.DiagnosticStats diagnostics) {
		long[] values = diagnostics.quadStats();
		long[] merged = diagnostics.mergeStats();
		for (int i = 0; i < quadStats.length; i++) {
			quadStats[i] += values[i];
			mergeStats[i] += merged[i];
		}
	}

	private static boolean sameSnapshot(TerrainPreparation.Frame first, TerrainPreparation.Frame second) {
		if (!first.camera().equals(second.camera()) || !first.policy().equals(second.policy()) || first.sections().size() != second.sections().size()) {
			return false;
		}
		for (int i = 0; i < first.sections().size(); i++) {
			TerrainPreparation.SectionInput a = first.sections().get(i);
			TerrainPreparation.SectionInput b = second.sections().get(i);
			if (!a.identity().equals(b.identity()) || Float.compare(a.visibility(), b.visibility()) != 0 || a.layers().size() != b.layers().size()) {
				return false;
			}
			for (int layer = 0; layer < a.layers().size(); layer++) {
				TerrainPreparation.LayerInput firstLayer = a.layers().get(layer);
				TerrainPreparation.LayerInput secondLayer = b.layers().get(layer);
				if (firstLayer.layer() != secondLayer.layer() || firstLayer.lod() != secondLayer.lod()) {
					return false;
				}
			}
		}
		return true;
	}

	private TerrainPreparation.Frame snapshotFrame(LevelRenderer renderer, Vec3 camera, long generation) {
		List<SectionRenderDispatcher.RenderSection> visible = visibleSections(renderer);
		List<TerrainPreparation.SectionInput> sections = new java.util.ArrayList<>(visible.size());
		for (SectionRenderDispatcher.RenderSection section : visible) {
			BlockPos origin = section.getRenderOrigin();
			SectionMesh mesh = section.getSectionMesh();
			long identity = Integer.toUnsignedLong(System.identityHashCode(mesh));
			long entryIdentity = 1L;
			for (ChunkSectionLayer identityLayer : ChunkSectionLayer.values()) {
				entryIdentity = 31L * entryIdentity + System.identityHashCode(DirectUberBuffer.entry(identityLayer, mesh));
				entryIdentity = 31L * entryIdentity + System.identityHashCode(DirectUberBuffer.indexEntry(identityLayer, mesh));
			}
			TerrainPreparation.SectionIdentity sectionIdentity = new TerrainPreparation.SectionIdentity(section.index, identity, entryIdentity & Long.MAX_VALUE);
			List<TerrainPreparation.LayerInput> layers = new java.util.ArrayList<>();
			for (ChunkSectionLayer mcLayer : ChunkSectionLayer.values()) {
				TerrainPreparation.Layer layer = TerrainPreparation.Layer.values()[mcLayer.ordinal()];
				SectionMesh.SectionDraw draw = mesh.getSectionDraw(mcLayer);
				if (draw == null) {
					continue;
				}
				DirectUberBuffer.Entry normalEntry = DirectUberBuffer.entry(mcLayer, mesh);
				DirectUberBuffer.Entry indexEntry = DirectUberBuffer.indexEntry(mcLayer, mesh);
				if (normalEntry == null || normalEntry.format() == TerrainVertex.COMPACT && !compactVertices()) {
					continue;
				}
				int indexCode = indexEntry == null ? 0 : draw.indexType().bytes == 2 ? 1 : 2;
				boolean lodRequested = !packCpuUnits() && lodEligible(mesh) && lodActive() && indexCode == 0
					&& mcLayer != ChunkSectionLayer.TRANSLUCENT && ring(camera, origin) >= lodMinRing;
				DirectUberBuffer.Entry lodEntry = lodRequested ? lodEntry(mcLayer, mesh) : null;
				boolean selectedLod = lodEntry != null;
				DirectUberBuffer.Entry entry = selectedLod ? lodEntry : normalEntry;
				int[] faces = entry.faces() == null ? emptyFaces : entry.faces();
				int[] merged = entry.merged() == null ? null : entry.merged();
				int flags = indexCode | entry.format().ordinal() << 2;
				if (entry.faces() != null) {
					flags |= 1 << 4;
				}
				int recordQuads = selectedLod ? quadCount(faces) : draw.indexCount() / 6;
				PackMetadata metadata = packMetadata(mcLayer, entry, recordQuads);
				layers.add(new TerrainPreparation.LayerInput(sectionIdentity, layer, origin.getX(), origin.getY(), origin.getZ(),
					section.getVisibility(frameMillis), entry.address(), indexEntry == null ? 0L : indexEntry.address(),
					entry.format() == TerrainVertex.COMPACT ? TerrainPreparation.VertexFormat.COMPACT : TerrainPreparation.VertexFormat.BLOCK,
					indexCode == 0 ? TerrainPreparation.IndexFormat.NONE : indexCode == 1 ? TerrainPreparation.IndexFormat.SHORT : TerrainPreparation.IndexFormat.INT,
					recordQuads, faces, merged, flags, selectedLod,
					metadata == null ? 0L : metadata.address(), metadata == null ? 0 : metadata.stride(),
					metadata == null ? -1L : metadata.generation(), metadata == null ? 0L : metadata.requirements()));
			}
			sections.add(new TerrainPreparation.SectionInput(sectionIdentity, origin.getX(), origin.getY(), origin.getZ(), section.getVisibility(frameMillis), layers));
		}
		TerrainPreparation.Camera snapshotCamera = new TerrainPreparation.Camera(cameraX, cameraY, cameraZ, offsetX, offsetY, offsetZ);
		TerrainPreparation.PreparationPolicy policy = new TerrainPreparation.PreparationPolicy(
			expandFrame, indexed, false, quadStatsEnabled, faceCulling, lodActive(), lodDropPlants, lodMinRing,
			AbSwitch.is("skipSolid"), AbSwitch.is("skipCutout"), AbSwitch.is("skipTranslucent"), AbSwitch.is("skipFar"), AbSwitch.is("skipPlants"), packCpuUnits());
		return new TerrainPreparation.Frame(generation, generation, snapshotCamera, sections, policy);
	}

	public void onFrameSubmitted(long timestampNanos) {
		if (!chunkTiming) {
			return;
		}
		if (terrainDrawIssued) {
			ChunkTiming.recordPoint(ChunkTiming.Stage.FIRST_FRAME, timestampNanos);
			for (int i = 0; i < firstDrawnPending.size(); i++) {
				long section = firstDrawnPending.getLong(i);
				ChunkTiming.markSectionFirstDrawn(section, timestampNanos);
				firstDrawnMarked.add(section);
			}
		}
		firstDrawnPending.clear();
		firstDrawnQueued.clear();
		terrainDrawIssued = false;
	}

	public void renderGroup(ChunkSectionsToRender toRender, ChunkSectionLayerGroup group, GpuSampler sampler) {
		lodRenderPath.render();
		if (containsTranslucent(group)) {
			RenderTarget target = group.outputTarget();
			pathTracer.render(target.getColorTextureView(), target.getDepthTextureView());
		}
		if (expandFrame && !cullFrame) {
			if (expanded != null) {
				try (RenderPass pass = createPass(toRender, group, sampler, unitsSlice(expanded, expandedUnitsBytes))) {
					drawRecordLayers(pass, group);
					resolveOit(pass, group);
				}
				terrainDrawIssued = true;
			} else if (!drawCpuUnits(toRender, group, sampler)) {
				submitFeatureCull(group);
				return;
			}
			submitFeatureCull(group);
			return;
		}
		if (!cullFrame) {
			if (units == null) {
				submitFeatureCull(group);
				return;
			}
			try (RenderPass pass = createPass(toRender, group, sampler, units)) {
				drawLayers(pass, group, null, 0L);
				resolveOit(pass, group);
			}
			terrainDrawIssued = true;
			submitFeatureCull(group);
			return;
		}
		GpuBufferSlice source;
		int count;
		int firstSolid;
		int firstCutout;
		int firstTranslucent;
		boolean fromArgs;
		long countArgsOffset;
		if (expandFrame) {
			if (expanded == null) {
				cullFrame = false;
				effectiveFrameMode = OcclusionMode.CPU_BFS;
				if (!drawCpuUnits(toRender, group, sampler)) {
					submitFeatureCull(group);
					return;
				}
				submitFeatureCull(group);
				return;
			}
			source = unitsSlice(expanded, expandedUnitsBytes);
			count = expandCapacity;
			firstSolid = 0;
			firstCutout = 0;
			firstTranslucent = 0;
			fromArgs = true;
			countArgsOffset = expandedUnitsBytes;
		} else {
			if (units == null) {
				submitFeatureCull(group);
				return;
			}
			source = units;
			count = packCpuUnits() ? packWriter().count() : writer.count();
			firstSolid = firstUnit[0];
			firstCutout = firstUnit[1];
			firstTranslucent = firstUnit[2];
			fromArgs = false;
			countArgsOffset = 0L;
		}
		RenderTarget target = group.outputTarget();
		boolean opaque = false;
		for (ChunkSectionLayer layer : group.layers()) {
			if (layer == ChunkSectionLayer.SOLID || layer == ChunkSectionLayer.CUTOUT) {
				opaque = true;
				break;
			}
		}
		long unitsBytes = Mth.roundToward((long) count * PackTerrainUnits.unitBytes(packCpuUnits()), UNIT_ALIGNMENT);
		long size = unitsBytes + 64;
		if (opaque) {
			GpuBufferSlice outA = allocateOutput(size);
			OcclusionMode mode = selectedOcclusionMode();
			effectiveFrameMode = mode;
			boolean single = mode == OcclusionMode.SINGLE_PHASE_HIZ;
			boolean issued = false;
			try {
				MetalAccess.cullTerrain(0, mode.code(), source, count, firstSolid, firstCutout, firstTranslucent,
					TerrainShaderPatch.VERTICES_PER_UNIT, unitSections, outA, outA.offset() + unitsBytes,
					single ? target.getDepthTextureView() : null, cullClip(), cullPreviousClip(), cullCameraX(), cullCameraY(), cullCameraZ(),
					cullOffsetX(), cullOffsetY(), cullOffsetZ(), fromArgs, countArgsOffset);
				issued = true;
			} catch (RuntimeException exception) {
				LOGGER.warn("Terrain occlusion failed; using CPU-visible terrain", exception);
			}
			if (!issued) {
				cullFrame = false;
				effectiveFrameMode = OcclusionMode.CPU_BFS;
				try (RenderPass pass = createPass(toRender, group, sampler, source)) {
					if (expandFrame) {
						drawRecordLayers(pass, group);
					} else {
						drawLayers(pass, group, null, 0L);
					}
					resolveOit(pass, group);
				}
				terrainDrawIssued = true;
				submitFeatureCull(group);
				return;
			}
			if (single) {
				singlePhaseOutput = outA;
				singlePhaseUnitsBytes = unitsBytes;
				singlePhaseGroup = group;
				try (RenderPass pass = createPass(toRender, group, sampler, unitsSlice(outA, unitsBytes))) {
					drawLayers(pass, group, outA, unitsBytes);
					resolveOit(pass, group);
				}
			} else {
				try (RenderPass pass = createPass(toRender, group, sampler, unitsSlice(outA, unitsBytes))) {
					drawLayers(pass, group, outA, unitsBytes);
					resolveOit(pass, group);
				}
				GpuBufferSlice outB = allocateOutput(size);
				phaseB = outB;
				phaseBGroup = group;
				try {
					MetalAccess.cullTerrain(1, mode.code(), source, count, firstSolid, firstCutout, firstTranslucent,
						TerrainShaderPatch.VERTICES_PER_UNIT, unitSections, outB, outB.offset() + unitsBytes, target.getDepthTextureView(), cullClip(), cullPreviousClip(),
						cullCameraX(), cullCameraY(), cullCameraZ(), cullOffsetX(), cullOffsetY(), cullOffsetZ(), fromArgs, countArgsOffset);
				} catch (RuntimeException exception) {
					LOGGER.warn("Terrain occlusion phase B failed; using CPU-visible terrain", exception);
					cullFrame = false;
					effectiveFrameMode = OcclusionMode.CPU_BFS;
					phaseB = null;
					phaseBGroup = null;
					try (RenderPass pass = createPass(toRender, group, sampler, source)) {
						drawRecordLayers(pass, group);
						resolveOit(pass, group);
					}
					terrainDrawIssued = true;
				}
				if (phaseB != null) {
					try (RenderPass pass = createPass(toRender, group, sampler, unitsSlice(outB, unitsBytes))) {
						drawLayers(pass, group, outB, unitsBytes);
						resolveOit(pass, group);
					}
				}
			}
			terrainDrawIssued = true;
			submitFeatureCull(group);
		} else if (singlePhaseOutput != null && singlePhaseGroup == group) {
			GpuBufferSlice output = singlePhaseOutput;
			try (RenderPass pass = createPass(toRender, group, sampler, unitsSlice(output, singlePhaseUnitsBytes))) {
				drawLayers(pass, group, output, singlePhaseUnitsBytes);
				resolveOit(pass, group);
			}
			terrainDrawIssued = true;
		} else if (phaseB != null && phaseBGroup == group) {
			GpuBufferSlice outB = phaseB;
			try (RenderPass pass = createPass(toRender, group, sampler, unitsSlice(outB, unitsBytes))) {
				drawLayers(pass, group, outB, unitsBytes);
				resolveOit(pass, group);
			}
			terrainDrawIssued = true;
		} else if (expandFrame) {
			try (RenderPass pass = createPass(toRender, group, sampler, source)) {
				drawRecordLayers(pass, group);
				resolveOit(pass, group);
			}
			terrainDrawIssued = true;
		} else {
			try (RenderPass pass = createPass(toRender, group, sampler, source)) {
				drawLayers(pass, group, null, 0L);
				resolveOit(pass, group);
			}
			terrainDrawIssued = true;
		}
	}

	private boolean drawCpuUnits(ChunkSectionsToRender toRender, ChunkSectionLayerGroup group, GpuSampler sampler) {
		if (units == null) {
			return false;
		}
		try (RenderPass pass = createPass(toRender, group, sampler, units)) {
			drawLayers(pass, group, null, 0L);
			resolveOit(pass, group);
		}
		terrainDrawIssued = true;
		return true;
	}

	private void submitFeatureCull(ChunkSectionLayerGroup group) {
		if (group != ChunkSectionLayerGroup.OPAQUE || this.featureRequestIssued) {
			return;
		}
		this.featureRequestIssued = true;
		FeatureInstanceStream stream = EntityFeatureRenderer.currentStream();
		if (stream == null || !stream.valid() || stream.count() == 0) {
			return;
		}
		RenderTarget target = group.outputTarget();
		GpuTextureView depth = target.getDepthTextureView();
		if (depth != this.featureDepth) {
			this.featureMask = null;
			this.featureDepth = depth;
			this.featurePreviousClip = null;
			this.featureHistoryValid = false;
		}
		stream.clearMask();
		stream.markMaskRequested();
		FeatureMask previous = this.featureMask;
		if (previous != null && previous.generation() + 1L == this.preparationGeneration && previous.depth() == depth
			&& previous.count() == stream.count() && MetalAccess.completedFrames() >= previous.frame()) {
			MemorySegment mask = MetalAccess.contents(previous.output().buffer()).asSlice(previous.output().offset(), (long) previous.count() * Integer.BYTES);
			stream.acceptPreviousMask(mask, previous.count(), previous.generation(), this.preparationGeneration - 1L);
		}
		this.featureMask = null;
		if (!MetalAccess.featureHizSupported() || this.clip == null || this.clip.length != 16) {
			stream.markFailOpen();
			return;
		}
		try {
			GpuDevice device = RenderSystem.getDevice();
			GpuBufferSlice input = device.createCommandEncoder().transientMemory().uploadGpu(stream.buffer(), UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
			GpuBufferSlice output = allocateOutput((long) stream.count() * Integer.BYTES);
			float[] previousClip = this.featureHistoryValid && this.featurePreviousClip != null ? this.featurePreviousClip : this.clip;
			MetalAccess.featureCull(input, stream.count(), output, depth, this.clip, previousClip);
			this.featureMask = new FeatureMask(output, stream.count(), depth, MetalAccess.submittedFrames() + 1L, this.preparationGeneration);
			this.featurePreviousClip = java.util.Arrays.copyOf(this.clip, this.clip.length);
			this.featureHistoryValid = true;
		} catch (RuntimeException exception) {
			this.featureMask = null;
			this.featureHistoryValid = false;
			stream.markFailOpen();
		}
	}

	private record FeatureMask(GpuBufferSlice output, int count, GpuTextureView depth, long frame, long generation) {
	}

	private RenderPass createPass(ChunkSectionsToRender toRender, ChunkSectionLayerGroup group, GpuSampler sampler, GpuBufferSlice unitsSlice) {
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget target = group.outputTarget();
		RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> "MTLRender terrain " + group.label(),
			target.getColorTextureView(),
			Optional.empty(),
			target.getDepthTextureView(),
			OptionalDouble.empty()
		);
		RenderSystem.bindDefaultUniforms(pass);
		pass.bindTexture("Sampler0", toRender.textureView(), sampler);
		pass.bindTexture("Sampler2", minecraft.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		pass.setUniform("ChunkSection", sectionInfo);
		pass.setUniform(TerrainPipelines.UNITS, unitsSlice);
		if (indexed) {
			pass.setIndexBuffer(quadIndices, IndexType.SHORT);
		}
		return pass;
	}

	private GpuBufferSlice allocateOutput(long size) {
		return RenderSystem.getDevice().createCommandEncoder().transientMemory()
			.allocateGpuMapped(size, 256, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_INDIRECT_PARAMETERS, size, 1)
			.slice();
	}

	private static GpuBufferSlice unitsSlice(GpuBufferSlice out, long unitsBytes) {
		return new GpuBufferSlice(out.buffer(), out.offset(), unitsBytes);
	}

	private void drawLayers(RenderPass pass, ChunkSectionLayerGroup group, GpuBufferSlice out, long unitsBytes) {
		boolean mesh = selectedPath() == TerrainDrawPath.MESH && meshReady && out == null;
		TerrainPipelines selected = pipelines();
		for (ChunkSectionLayer layer : group.layers()) {
			int count = unitCount[layer.ordinal()];
			if (count > 0 || out != null) {
				Optional<PackTerrainPipelines.Selection> pack = packSelection(layer);
				GpuBufferSlice packUnits = out == null
					? (expanded == null ? units : unitsSlice(expanded, expandedUnitsBytes))
					: unitsSlice(out, unitsBytes);
				if (pack.isPresent()) {
					if (!bindPackSelection(pass, pack.orElseThrow(), packUnits)) continue;
				} else {
					RenderPipeline depth = mesh ? selected.meshDepthPrepass(layer) : selected.depthPrepass(layer);
					if (depth != null) {
						pass.setPipeline(depth);
						if (mesh) {
							drawLayerMesh(pass, count);
						} else {
							drawLayerUnits(pass, layer, count, out, unitsBytes);
						}
					}
					pass.setPipeline(mesh ? selected.mesh(layer) : selected.get(layer));
				}
				if (pack.isPresent()) {
					drawLayerUnits(pass, layer, count, out, unitsBytes);
				} else if (mesh) {
					drawLayerMesh(pass, count);
				} else {
					drawLayerUnits(pass, layer, count, out, unitsBytes);
				}
			}
		}
	}

	private static void drawLayerMesh(RenderPass pass, int count) {
		if (count > 0) {
			MetalAccess.drawMesh(count, TerrainMeshShader.OBJECT_THREADS, TerrainMeshShader.MESH_THREADS);
		}
	}

	private void drawRecordLayers(RenderPass pass, ChunkSectionLayerGroup group) {
		for (ChunkSectionLayer layer : group.layers()) {
			if (visibleWriter.count() > 0 && (layerMask & 1 << layer.ordinal()) != 0) {
				Optional<PackTerrainPipelines.Selection> pack = packSelection(layer);
				if (pack.isPresent()) {
					if (bindPackSelection(pass, pack.orElseThrow(), unitsSlice(expanded, expandedUnitsBytes))) drawLayerRecords(pass, layer);
					continue;
				}
				TerrainPipelines selected = pipelines();
				RenderPipeline depth = selected.depthPrepass(layer);
				if (depth != null) {
					pass.setPipeline(depth);
					drawLayerRecords(pass, layer);
				}
				pass.setPipeline(selected.get(layer));
				drawLayerRecords(pass, layer);
			}
		}
	}

	private void drawLayerRecords(RenderPass pass, ChunkSectionLayer layer) {
		pass.drawIndexedIndirect(new GpuBufferSlice(expanded.buffer(), expanded.offset() + expandedUnitsBytes + layer.ordinal() * 20L, 20), 1);
	}

	private void drawLayerUnits(RenderPass pass, ChunkSectionLayer layer, int count, GpuBufferSlice out, long unitsBytes) {
		if (out == null) {
			drawUnits(pass, count, firstUnit[layer.ordinal()]);
		} else {
			pass.drawIndexedIndirect(new GpuBufferSlice(out.buffer(), out.offset() + unitsBytes + (long) layer.ordinal() * 20L, 20), 1);
		}
	}

	private void drawUnits(RenderPass pass, int count, int first) {
		if (indexed) {
			pass.drawIndexed(TerrainShaderPatch.VERTICES_PER_UNIT, count, 0, 0, first);
		} else {
			pass.draw(TerrainShaderPatch.VERTICES_PER_UNIT * count, 1, 0, first);
		}
	}

	private static GpuBuffer createQuadIndices(GpuDevice device) {
		ByteBuffer indices = ByteBuffer.allocate(TerrainShaderPatch.VERTICES_PER_UNIT * Short.BYTES).order(ByteOrder.nativeOrder());
		for (int quad = 0; quad < TerrainShaderPatch.QUADS_PER_UNIT; quad++) {
			short base = (short) (quad * 4);
			indices.putShort(base).putShort((short) (base + 1)).putShort((short) (base + 2))
				.putShort((short) (base + 2)).putShort((short) (base + 3)).putShort(base);
		}
		return device.createBuffer(() -> "MTLRender terrain quad indices", GpuBuffer.USAGE_INDEX, indices.flip());
	}

	private TerrainPipelines selectedPipelines() {
		return pipelines();
	}

	private Optional<PackTerrainPipelines.Selection> packSelection(ChunkSectionLayer layer) {
		if (!packState.packActive()) return Optional.empty();
		Optional<PackTerrainPipelines.Selection> selection = packState.pipeline(layer);
		if (selection.isEmpty()) return Optional.empty();
		PackTerrainPipelines.Selection value = selection.orElseThrow();
		if (!value.usable()) {
			value.failureContext().ifPresent(failure -> LOGGER.warn(
				"Skipping shader pack terrain program for {}: {} ({})", layer, failure.message(), failure.code()));
			return Optional.of(new PackTerrainPipelines.Selection(0L, value.descriptor(), value.requirements(),
				value.generation(), value.vertexMode(), value.failureContext()));
		}
		return selection;
	}

	private boolean bindPackSelection(RenderPass pass, PackTerrainPipelines.Selection selection, GpuBufferSlice packUnits) {
		if (!selection.usable()) return false;
		try {
			MetalAccess.usePackPipeline(selection.pipeline());
			for (dev.mikuteto.mtlrender.backend.PackPipelineDesc.Binding binding : selection.descriptor().bindings()) {
				switch (binding.kind()) {
					case BUFFER -> bindPackBuffer(binding, packUnits);
					case TEXTURE -> bindPackTexture(binding);
					case SAMPLER -> { }
				}
			}
			return true;
		} catch (RuntimeException exception) {
			LOGGER.warn("Skipping shader pack terrain program for pipeline generation {}: {}", selection.generation(), exception.getMessage());
			return false;
		}
	}

	private void bindPackBuffer(dev.mikuteto.mtlrender.backend.PackPipelineDesc.Binding binding, GpuBufferSlice packUnits) {
		GpuBufferSlice slice = switch (binding.name()) {
			case "ChunkSection" -> sectionInfo;
			case TerrainPipelines.UNITS -> packUnits;
			default -> null;
		};
		if (slice == null || slice.buffer() == null) throw new IllegalStateException("missing terrain buffer " + binding.name());
		MetalAccess.bindPackUniform(binding.slot(), slice);
	}

	private void bindPackTexture(dev.mikuteto.mtlrender.backend.PackPipelineDesc.Binding binding) {
		GpuTextureView view = packTexture(binding.name());
		if (view == null) throw new IllegalStateException("missing terrain texture " + binding.name());
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		int samplerSlot = binding.slot();
		MetalAccess.bindPackTexture(binding.slot(), samplerSlot, view, sampler, binding.stages());
	}

	private GpuTextureView packTexture(String name) {
		Minecraft minecraft = Minecraft.getInstance();
		if (name.equals("Sampler0")) return minecraft.level == null ? null : minecraft.getTextureManager()
			.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		if (name.equals("Sampler2")) return minecraft.gameRenderer.lightmap();
		if (packRuntime == null) return null;
		return packRuntime.resources().targets().flatMap(targets -> {
			GpuTextureView view = targets.colorViews().get(name);
			if (view != null) return Optional.of(view);
			view = targets.depthViews().get(name);
			if (view != null) return Optional.of(view);
			return Optional.ofNullable(targets.shadowViews().get(name));
		}).orElse(null);
	}

	private PackTerrainRenderState snapshotPackState(Minecraft minecraft) {
		String configured = Config.get().shaderPack;
		PackTerrainRenderState state = PackTerrainRenderState.select(configured, () -> snapshotConfiguredPackState(minecraft));
		if (packDiagnostics) {
			packSolidProgram = diagnosticProgram(state.pipeline(ChunkSectionLayer.SOLID), ChunkSectionLayer.SOLID);
			packCutoutProgram = diagnosticProgram(state.pipeline(ChunkSectionLayer.CUTOUT), ChunkSectionLayer.CUTOUT);
			packWaterProgram = diagnosticProgram(state.pipeline(ChunkSectionLayer.TRANSLUCENT), ChunkSectionLayer.TRANSLUCENT);
		}
		return state;
	}

	private static String diagnosticProgram(Optional<PackTerrainPipelines.Selection> selection, ChunkSectionLayer layer) {
		if (selection.isEmpty()) return "missing";
		PackTerrainPipelines.Selection value = selection.orElseThrow();
		if (!value.usable()) return "failure:" + value.failureContext().map(PackTerrainPipelines.Failure::code).orElse("unknown");
		return PackTerrainPipelines.programFor(layer).sourceName();
	}

	private void observePackMetadata(PackMetadata metadata, int quads) {
		if (!packDiagnostics || metadata == null || metadata.address() <= 0L || metadata.stride() != PackTerrainUnits.METADATA_STRIDE || quads <= 0) return;
		packMetadataRecords += quads;
		packMetadataAddresses++;
		if (packMetadataFirstAddress == 0L) packMetadataFirstAddress = metadata.address();
		packMetadataLastAddress = metadata.address();
		MemorySegment record = MemorySegment.ofAddress(metadata.address()).reinterpret(metadata.stride());
		if (record.get(ValueLayout.JAVA_INT_UNALIGNED, PackTerrainLayout.BLOCK_ID_OFFSET) != 0) packBlockIds++;
		if (record.get(ValueLayout.JAVA_INT_UNALIGNED, PackTerrainLayout.NORMAL_TANGENT_OFFSET) != 0) packTangents++;
	}

	private PackTerrainRenderState snapshotConfiguredPackState(Minecraft minecraft) {
		if (minecraft.level == null) {
			return PackTerrainRenderState.none();
		}
		if (packRuntime == null) {
			packRuntime = PackRuntimeService.get();
		}
		Optional<PackRuntimeService.PackSnapshot> activePack = packRuntime.activePack();
		if (activePack.isEmpty()) {
			return PackTerrainRenderState.none();
		}
		String dimension = minecraft.level.dimension().identifier().toString();
		ShaderPack pack = activePack.get().pack();
		if (!hasTerrainProgram(pack, dimension)) {
			return PackTerrainRenderState.none();
		}
		if (packPipelines == null) {
			packPipelines = new PackTerrainPipelines();
		}
		PackTerrainRequirements requirements = RustMesher.packRequirements();
			Object idMap = packPipelines.idMap();
		long idMapGeneration = idMap == null ? -1L : Integer.toUnsignedLong(System.identityHashCode(idMap));
		long metadataGeneration = RustMesher.packGeneration();
		long runtimeGeneration = packRuntime.generation();
		PackTerrainPipelines.VertexMode vertexMode = compactVertices
			? PackTerrainPipelines.VertexMode.COMPACT : PackTerrainPipelines.VertexMode.BLOCK;
		return PackTerrainRenderState.capture(packRuntime, packPipelines, full, dimension, activePack.orElseThrow(), runtimeGeneration,
			requirements, metadataGeneration, vertexMode, idMapGeneration);
	}

	private static boolean hasTerrainProgram(ShaderPack pack, String dimension) {
		var programs = pack.programSet(dimension);
		return java.util.Arrays.stream(ChunkSectionLayer.values())
			.map(PackTerrainPipelines::programFor)
			.anyMatch(program -> programs.resolve(program).isPresent());
	}

	private TerrainPipelines pipelines() {
		if (AbSwitch.is("cutoutPrepass")) {
			return prepass;
		}
		if ("halfVaryings".equals(AbSwitch.NAME) && AbSwitch.b()) {
			return full;
		}
		return halfVaryings ? half : full;
	}

	private boolean pipelinesReady() {
		meshReady = selectedPath() == TerrainDrawPath.MESH;
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			if (!layer.vertexFormat().equals(DefaultVertexFormat.BLOCK)) {
				disable("layer " + layer + " does not use the BLOCK vertex format");
				return false;
			}
			if (!AbSwitch.NAME.isEmpty()) {
				if (!compiles(half, layer)) {
					disable("terrain pipeline for " + layer + " failed to compile");
					return false;
				}
				if (!compiles(full, layer)) {
					disable("terrain pipeline for " + layer + " failed to compile");
					return false;
				}
				if ("cutoutPrepass".equals(AbSwitch.NAME) && !compiles(prepass, layer)) {
					disable("terrain pipeline for " + layer + " failed to compile");
					return false;
				}
			} else if (!compiles(pipelines(), layer)) {
				disable("terrain pipeline for " + layer + " failed to compile");
				return false;
			}
		}
		if (meshReady) {
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				if (!compilesMesh(selectedPipelines(), layer)) {
					meshReady = false;
					break;
				}
			}
		}
		return true;
	}

	private boolean compilesMesh(TerrainPipelines set, ChunkSectionLayer layer) {
		RenderPipeline depth = set.meshDepthPrepass(layer);
		return compilesMesh(set, set.mesh(layer)) && (depth == null || compilesMesh(set, depth));
	}

	private static boolean compilesMesh(TerrainPipelines set, RenderPipeline pipeline) {
		return MetalAccess.precompileTerrainMesh(pipeline, TerrainMeshShader.program(set.half())).isValid();
	}

	private boolean compiles(TerrainPipelines set, ChunkSectionLayer layer) {
		RenderPipeline depth = set.depthPrepass(layer);
		return compiles(set.get(layer)) && (depth == null || compiles(depth));
	}

	private static boolean compiles(RenderPipeline pipeline) {
		return RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
	}

	private void disable(String reason) {
		failed = true;
		LOGGER.warn("MTLRender terrain renderer disabled, falling back to vanilla: {}", reason);
		if (compactVertices) {
			Minecraft.getInstance().levelExtractor.allChanged();
		}
	}

	private boolean packCpuUnits() {
		if (!packState.packActive()) {
			return false;
		}
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			Optional<PackTerrainPipelines.Selection> selection = packState.pipeline(layer);
			if (selection.isPresent() && selection.orElseThrow().usable() && selection.orElseThrow().requirements().needsExtraData()) {
				return true;
			}
		}
		return false;
	}

	private UnitWriter packWriter() {
		if (packWriter == null) {
			packWriter = new UnitWriter(true);
		}
		return packWriter;
	}

	private PackMetadata packMetadata(ChunkSectionLayer layer, DirectUberBuffer.Entry entry, int quads) {
		if (!packCpuUnits()) {
			return null;
		}
		Optional<PackTerrainPipelines.Selection> selection = packState.pipeline(layer);
		if (selection.isEmpty() || !selection.orElseThrow().usable() || !selection.orElseThrow().requirements().needsExtraData()
			|| !entry.hasPackMetadata() || !packState.metadataCompatible(entry, packState.blockIdMapGeneration())
			|| entry.packMetadataAddress() <= 0L || !PackTerrainUnits.validMetadata(entry.packMetadataAddress(), PackTerrainUnits.METADATA_STRIDE, quads)) {
			return null;
		}
		PackMetadata metadata = new PackMetadata(entry.packMetadataAddress(), PackTerrainUnits.METADATA_STRIDE, entry.metadataGeneration(), entry.metadataRequirements());
		observePackMetadata(metadata, quads);
		return metadata;
	}

	private void prepareCollectedCpuUnits(LevelRenderer renderer, GpuDevice device) {
		collectUnits(renderer);
		UnitWriter.Result result = packWriter().result();
		units = result.count() == 0 ? null : device.createCommandEncoder().transientMemory()
			.uploadGpu(result.bytes(), UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
		lastUnits = result.count();
		lastQuads = result.quads();
		unitSections = lastSections;
	}

	private void collectUnits(LevelRenderer renderer) {
		UnitWriter output = packCpuUnits() ? packWriter() : writer;
		output.reset();
		SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
		List<SectionRenderDispatcher.RenderSection> visible = visibleSections(renderer);
		long now = frameMillis;
		if (dispatcher == null) {
			java.util.Arrays.fill(unitCount, 0);
			java.util.Arrays.fill(recordLayerUnits, 0);
			lastUnits = output.count();
			lastQuads = output.quads();
			lastSections = 0;
			unitSections = 0;
			return;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int size = visible.size();
		unitSections = CpuBfsVisibility.sectionCount(visible, section -> section.index);
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			int first = output.count();
			boolean reverse = layer == ChunkSectionLayer.TRANSLUCENT;
			boolean cullFaces = faceCulling && pipelines().get(layer).isCull();
			boolean skip = AbSwitch.is("skipSolid") && layer == ChunkSectionLayer.SOLID
				|| AbSwitch.is("skipCutout") && layer == ChunkSectionLayer.CUTOUT
				|| AbSwitch.is("skipTranslucent") && layer == ChunkSectionLayer.TRANSLUCENT;
			for (int i = 0; i < size; i++) {
				if (skip) {
					continue;
				}
				SectionRenderDispatcher.RenderSection section = CpuBfsVisibility.at(visible, i, reverse);
				writeSection(section, layer, now, cullFaces ? camera : null, camera, section.index * 4 + layer.ordinal(), output);
			}
			firstUnit[layer.ordinal()] = first;
			unitCount[layer.ordinal()] = output.count() - first;
		}
		for (int layer = 0; layer < unitCount.length; layer++) {
			recordLayerUnits[layer] = unitCount[layer];
		}
		lastUnits = output.count();
		lastQuads = output.quads();
		lastSections = visible.size();
	}

	private void writeSection(
		SectionRenderDispatcher.RenderSection section,
		ChunkSectionLayer layer,
		long now,
		Vec3 cullCamera,
		Vec3 camera,
		int slot,
		UnitWriter output
	) {
		if (AbSwitch.is("skipFar") && ring(camera, section.getRenderOrigin()) == 3) {
			return;
		}
		SectionMesh mesh = section.getSectionMesh();
		if (mesh instanceof MeshEntries entries && !entries.mtlrender$isCurrent()) {
			return;
		}
		BlockPos origin = section.getRenderOrigin();
		if (layer == ChunkSectionLayer.SOLID) {
			pathTracer.updateSection(origin, VoxelBricks.get(mesh));
		}
		SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
		if (draw == null) {
			return;
		}
		int ring = ring(camera, origin);
		float visibility = section.getVisibility(now);
		int quads = draw.indexCount() / 6;
		int statBase = (ring * 3 + layer.ordinal()) * 3;
		if (!draw.hasCustomIndexBuffer()) {
			DirectUberBuffer.Entry lodEntry = !packCpuUnits() && lodActive() && lodEligible(mesh) && ring >= lodMinRing ? lodEntry(layer, mesh) : null;
			DirectUberBuffer.Entry entry = lodEntry != null ? lodEntry : DirectUberBuffer.entry(layer, mesh);
			if (entry == null || entry.format() == TerrainVertex.COMPACT && !compactVertices()) {
				return;
			}
			if (lodEntry != null) {
				quads = quadCount(entry.faces());
			}
			if (quads == 0) {
				return;
			}
			queueFirstDrawn(origin);
			TerrainVertex format = entry.format();
			long vertices = entry.address();
			PackMetadata metadata = packMetadata(layer, entry, quads);
			if (packCpuUnits() && metadata == null) {
				return;
			}
			int[] faces = cullCamera == null ? null : entry.faces();
			if (faces == null) {
				quadStats[statBase + 2] += quads;
				mergeStats[statBase + 2] += quads;
				writeQuads(output, metadata, vertices, format, origin, visibility, 0, quads, slot);
			} else {
				int mask = FaceBuckets.visibleMask(cullCamera, origin.getX(), origin.getY(), origin.getZ());
				if (lodEntry != null && lodDropPlants) {
					mask &= ~(1 << FaceBuckets.UNASSIGNED);
				}
				writeVisibleFaces(output, metadata, vertices, format, origin, visibility, faces, entry.merged(), mask, statBase, slot);
			}
			return;
		}
		DirectUberBuffer.Entry entry = DirectUberBuffer.entry(layer, mesh);
		DirectUberBuffer.Entry indexEntry = DirectUberBuffer.indexEntry(layer, mesh);
		if (entry == null || entry.format() == TerrainVertex.COMPACT && !compactVertices() || indexEntry == null || quads == 0) {
			return;
		}
		queueFirstDrawn(origin);
		quadStats[statBase + 2] += quads;
		mergeStats[statBase + 2] += quads;
		int indexBytes = draw.indexType().bytes;
		int indexType = indexBytes == 2 ? INDEX_SHORT : INDEX_INT;
		long vertices = entry.address();
		long indices = indexEntry.address();
		TerrainVertex format = entry.format();
		PackMetadata metadata = packMetadata(layer, entry, quads);
		if (packCpuUnits() && metadata == null) {
			return;
		}
		for (int quad = 0; quad < quads; quad += TerrainShaderPatch.QUADS_PER_UNIT) {
			int count = Math.min(TerrainShaderPatch.QUADS_PER_UNIT, quads - quad);
			long address = indices + (long) quad * 6 * indexBytes;
			if (metadata == null) {
				output.unit(vertices, address, origin.getX(), origin.getY(), origin.getZ(), visibility, count, indexType, format.ordinal(), slot);
			} else {
				output.packUnit(vertices, address, metadata.address(), metadata.stride(), quad, origin.getX(), origin.getY(), origin.getZ(), visibility, count,
					indexType, format.ordinal() | PackTerrainUnits.PACK_FLAG, slot);
			}
		}
	}

	private void queueFirstDrawn(BlockPos origin) {
		if (!chunkTiming) {
			return;
		}
		long key = net.minecraft.core.SectionPos.asLong(origin);
		if (!firstDrawnMarked.contains(key) && firstDrawnQueued.add(key)) {
			firstDrawnPending.add(key);
		}
	}

	private void sortTranslucent(LevelRenderer renderer, Vec3 camera, GpuDevice device) {
		if (!translucentSortManaged()) {
			return;
		}
		boolean gpu = gpuTranslucentSort();
		if (!gpu && MetalAccess.submittedFrames() > 0 && MetalAccess.completedFrames() + 1 < MetalAccess.submittedFrames()) {
			return;
		}
		sortWriter.reset();
		List<SectionRenderDispatcher.RenderSection> visible = visibleSections(renderer);
		for (int i = 0; i < visible.size(); i++) {
			SectionRenderDispatcher.RenderSection section = visible.get(i);
			SectionMesh mesh = section.getSectionMesh();
			SectionMesh.SectionDraw draw = mesh.getSectionDraw(ChunkSectionLayer.TRANSLUCENT);
			if (draw == null || !draw.hasCustomIndexBuffer()) {
				continue;
			}
			DirectUberBuffer.Entry vertices = DirectUberBuffer.entry(ChunkSectionLayer.TRANSLUCENT, mesh);
			DirectUberBuffer.Entry indices = DirectUberBuffer.indexEntry(ChunkSectionLayer.TRANSLUCENT, mesh);
			if (vertices == null || indices == null) {
				continue;
			}
			int quads = draw.indexCount() / 6;
			boolean sample = sortCheck && i % 16 == 0;
			int[] order = !gpu || sample
				? TranslucentSort.order(vertices, quads, (float) camera.x, (float) camera.y, (float) camera.z, section.getRenderOrigin())
				: null;
			if (gpu) {
				sortWriter.add(vertices.address(), indices.address(), quads, draw.indexType().bytes, vertices.format().ordinal(),
					section.getRenderOrigin().getX(), section.getRenderOrigin().getY(), section.getRenderOrigin().getZ(),
					sample ? order : null);
			} else {
				TranslucentSort.write(indices, draw.indexType().bytes, order);
			}
		}
		if (gpuTranslucentSort() && sortWriter.count() > 0) {
			GpuBufferSlice records = device.createCommandEncoder().transientMemory().uploadGpu(sortWriter.bytes(), UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
			GpuBufferSlice expected = sortCheck && sortWriter.expectedBytes().remaining() > 0
				? device.createCommandEncoder().transientMemory().uploadGpu(sortWriter.expectedBytes(), UNIT_ALIGNMENT, GpuBuffer.USAGE_UNIFORM)
				: null;
			MetalAccess.sortTerrain(records, expected, sortWriter.count(), sortWriter.items(), sortWriter.indexCount(), sortWriter.maxPadded(),
				(float) camera.x, (float) camera.y, (float) camera.z);
		}
	}

	private void collectSections(LevelRenderer renderer) {
		records = new RecordWriter(packCpuUnits());
		visibleWriter.reset();
		expandCapacity = 0;
		long unitsTotal = MetalAccess.statTotal(MetalAccess.EXPAND_UNITS);
		long quadsTotal = MetalAccess.statTotal(MetalAccess.EXPAND_QUADS);
		lastUnits = (int) (unitsTotal - expandUnitsTotal);
		lastQuads = quadsTotal - expandQuadsTotal;
		expandUnitsTotal = unitsTotal;
		expandQuadsTotal = quadsTotal;
		SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
		List<SectionRenderDispatcher.RenderSection> visible = visibleSections(renderer);
		long now = frameMillis;
		if (dispatcher == null) {
			unitSections = 0;
			lastSections = 0;
			return;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int size = visible.size();
		int needed = CpuBfsVisibility.sectionCount(visible, section -> section.index);
		unitSections = needed;
		if (needed > recordSections) {
			recordSections = Math.max(needed, recordSections * 2);
			cachedUnits = new int[recordSections];
			cachedLayerUnits = new int[recordSections * ChunkSectionLayer.values().length];
			dirty = new boolean[recordSections];
			cachedLod = new boolean[recordSections];
			if (allDirty) {
				java.util.Arrays.fill(dirty, true);
			}
		}
		java.util.Arrays.fill(recordLayerUnits, 0);
		boolean skipFar = AbSwitch.is("skipFar");
		boolean lodActive = lodActive();
		for (int i = 0; i < size; i++) {
			SectionRenderDispatcher.RenderSection section = visible.get(i);
			int index = section.index;
			boolean lod = lodActive && ring(camera, section.getRenderOrigin()) >= lodMinRing;
			if (lod != cachedLod[index]) {
				cachedLod[index] = lod;
				dirty[index] = true;
			}
			if (dirty[index]) {
				dirty[index] = false;
				SectionMesh mesh = section.getSectionMesh();
				BlockPos origin = section.getRenderOrigin();
				int units = 0;
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					int capacity = writeLayerRecord(section, layer, mesh, origin, index, lod);
					int layerUnits = capacity == 0 ? 0 : capacity - 3;
					cachedLayerUnits[index * ChunkSectionLayer.values().length + layer.ordinal()] = layerUnits;
					units += capacity;
				}
				cachedUnits[index] = units;
			}
			if (!skipFar || ring(camera, section.getRenderOrigin()) != 3) {
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					recordLayerUnits[layer.ordinal()] += cachedLayerUnits[index * ChunkSectionLayer.values().length + layer.ordinal()];
				}
				visibleWriter.entry(index, section.getVisibility(now));
				expandCapacity += cachedUnits[index];
			}
		}
		lastSections = size;
	}

	private int writeLayerRecord(SectionRenderDispatcher.RenderSection section, ChunkSectionLayer layer, SectionMesh mesh, BlockPos origin, int sectionIndex, boolean lod) {
		int slot = sectionIndex * 3 + layer.ordinal();
		int recordSlot = sectionIndex * 4 + layer.ordinal();
		if (mesh instanceof MeshEntries entries && !entries.mtlrender$isCurrent()) {
			records.update(slot, 0L, 0L, 0, 0, 0, 0, 0, recordSlot, null, 0L, 0);
			return 0;
		}
		SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
		if (draw == null) {
			records.update(slot, 0L, 0L, 0, 0, 0, 0, 0, 0, null);
			return 0;
		}
		int quads = draw.indexCount() / 6;
		if (!draw.hasCustomIndexBuffer()) {
			DirectUberBuffer.Entry lodEntry = lod && lodEligible(mesh) ? lodEntry(layer, mesh) : null;
			DirectUberBuffer.Entry entry = lodEntry != null ? lodEntry : DirectUberBuffer.entry(layer, mesh);
			if (entry == null || entry.format() == TerrainVertex.COMPACT && !compactVertices()) {
				records.update(slot, 0L, 0L, 0, 0, 0, 0, 0, 0, null);
				return 0;
			}
			if (lodEntry != null) {
				quads = quadCount(entry.faces());
			}
			int[] faces = entry.faces();
			int flags = entry.format().ordinal() << 2;
			if (faces != null) {
				flags |= 1 << 4;
			}
			if (lodEntry != null && lodDropPlants) {
				flags |= 1 << 5;
			}
			PackMetadata metadata = packMetadata(layer, entry, quads);
			if (packCpuUnits() && metadata == null) {
				records.update(slot, 0L, 0L, 0, 0, 0, 0, 0, recordSlot, null, 0L, 0);
				return 0;
			}
			records.update(slot, entry.address(), 0L, origin.getX(), origin.getY(), origin.getZ(), quads, flags, recordSlot, faces,
				metadata == null ? 0L : metadata.address(), metadata == null ? 0 : metadata.stride());
			return (quads + TerrainShaderPatch.QUADS_PER_UNIT - 1) / TerrainShaderPatch.QUADS_PER_UNIT + 3;
		}
		DirectUberBuffer.Entry entry = DirectUberBuffer.entry(layer, mesh);
		DirectUberBuffer.Entry indexEntry = DirectUberBuffer.indexEntry(layer, mesh);
		if (entry == null || entry.format() == TerrainVertex.COMPACT && !compactVertices() || indexEntry == null) {
			records.update(slot, 0L, 0L, 0, 0, 0, 0, 0, 0, null);
			return 0;
		}
		int indexType = draw.indexType().bytes == 2 ? INDEX_SHORT : INDEX_INT;
		int flags = indexType | entry.format().ordinal() << 2;
		PackMetadata metadata = packMetadata(layer, entry, quads);
		if (packCpuUnits() && metadata == null) {
			records.update(slot, 0L, 0L, 0, 0, 0, 0, 0, recordSlot, null, 0L, 0);
			return 0;
		}
		records.update(slot, entry.address(), indexEntry.address(), origin.getX(), origin.getY(), origin.getZ(), quads, flags, recordSlot, null,
			metadata == null ? 0L : metadata.address(), metadata == null ? 0 : metadata.stride());
		return (quads + TerrainShaderPatch.QUADS_PER_UNIT - 1) / TerrainShaderPatch.QUADS_PER_UNIT + 3;
	}

	private float[] cullClip() {
		return shadowCullView == null ? clip : shadowCullView.clip();
	}

	private float[] cullPreviousClip() {
		return shadowCullView == null ? framePreviousClip : shadowCullView.clip();
	}

	private int cullCameraX() {
		return shadowCullView == null ? cameraX : shadowCullView.cameraX();
	}

	private int cullCameraY() {
		return shadowCullView == null ? cameraY : shadowCullView.cameraY();
	}

	private int cullCameraZ() {
		return shadowCullView == null ? cameraZ : shadowCullView.cameraZ();
	}

	private float cullOffsetX() {
		return shadowCullView == null ? offsetX : shadowCullView.offsetX();
	}

	private float cullOffsetY() {
		return shadowCullView == null ? offsetY : shadowCullView.offsetY();
	}

	private float cullOffsetZ() {
		return shadowCullView == null ? offsetZ : shadowCullView.offsetZ();
	}

	private int cullMask() {
		int mask = 0;
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			if (faceCulling && pipelines().get(layer).isCull()) {
				mask |= 1 << layer.ordinal();
			}
		}
		return mask;
	}

	private int layerMask() {
		int mask = 0;
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			boolean skip = AbSwitch.is("skipSolid") && layer == ChunkSectionLayer.SOLID
				|| AbSwitch.is("skipCutout") && layer == ChunkSectionLayer.CUTOUT
				|| AbSwitch.is("skipTranslucent") && layer == ChunkSectionLayer.TRANSLUCENT;
			if (!skip) {
				mask |= 1 << layer.ordinal();
			}
		}
		return layerMask = mask;
	}

	private void resolveOit(RenderPass pass, ChunkSectionLayerGroup group) {
		if (translucentOit.enabled() && containsTranslucent(group)) {
			MetalAccess.terrainOitResolve(translucentOit.k());
		}
	}

	private static boolean containsTranslucent(ChunkSectionLayerGroup group) {
		for (ChunkSectionLayer layer : group.layers()) {
			if (layer == ChunkSectionLayer.TRANSLUCENT) {
				return true;
			}
		}
		return false;
	}

	List<DhLodPass.ShadowPrepared> prepareShadowLod(
		List<SectionRenderDispatcher.RenderSection> shadowVisible,
		Vec3 shadowCamera,
		List<DhLodPass.Plane> shadowPlanes,
		DhShadowFrame frame,
		DhDepthResources depthResources) {
		java.util.Objects.requireNonNull(shadowVisible, "shadow visible sections");
		java.util.Objects.requireNonNull(shadowCamera, "shadow camera");
		java.util.Objects.requireNonNull(shadowPlanes, "shadow planes");
		java.util.Objects.requireNonNull(frame, "shadow frame");
		java.util.Objects.requireNonNull(depthResources, "depth resources");
		if (!frame.enabled() || !lodActive()) return List.of();
		List<DhLodPass.ShadowPrepared> result = new java.util.ArrayList<>();
		for (SectionRenderDispatcher.RenderSection section : shadowVisible) {
			BlockPos origin = section.getRenderOrigin();
			if (!DhLodPass.shadowVisible(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16.0F,
				origin.getY() + 16.0F, origin.getZ() + 16.0F, shadowPlanes)) continue;
			SectionMesh mesh = section.getSectionMesh();
			if (!lodEligible(mesh) || ring(shadowCamera, origin) < lodMinRing) continue;
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				if (layer == ChunkSectionLayer.TRANSLUCENT && !frame.canRenderTranslucent()) continue;
				if (layer != ChunkSectionLayer.TRANSLUCENT && !frame.canRenderTerrain()) continue;
				SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
				if (draw == null || draw.hasCustomIndexBuffer()) continue;
				DirectUberBuffer.Entry entry = DirectUberBuffer.lodEntry(layer, mesh);
				DhRenderContract.MaterialKind kind = layer == ChunkSectionLayer.TRANSLUCENT
					? DhRenderContract.MaterialKind.WATER : DhRenderContract.MaterialKind.TERRAIN;
				DhLodPass.Material material = new DhLodPass.Material(layer, kind, 0,
					new DhRenderContract.WorldOrigin(origin.getX(), origin.getY(), origin.getZ()));
				DhLodPass.prepareShadow(entry, material, 0, frame, depthResources).ifPresent(result::add);
			}
		}
		return List.copyOf(result);
	}

	private void refreshDhPrograms(Minecraft minecraft) {
		if (!lodEnabled || Config.get().shaderPack == null || Config.get().shaderPack.isEmpty() || minecraft.level == null) {
			return;
		}
		PackManager.get().active().ifPresent(pack -> dhPrograms = DhLodPass.availablePrograms(
			pack.programSet(minecraft.level.dimension().identifier().toString())));
	}

	private DirectUberBuffer.Entry lodEntry(ChunkSectionLayer layer, SectionMesh mesh) {
		DirectUberBuffer.Entry entry = DirectUberBuffer.lodEntry(layer, mesh);
		if (entry == null || !dhPrograms.contains(DhLodPass.programFor(layer))) {
			return entry;
		}
		return entry;
	}

	private static boolean lodEligible(SectionMesh mesh) {
		if (!(mesh instanceof MeshEntries entries)) {
			return true;
		}
		StaticBlockEntityBakePublication publication = entries.mtlrender$staticBlockEntityBakePublication();
		return publication == null || publication.instances().stream().noneMatch(StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance::hasStaticPart);
	}

	private static int ring(Vec3 camera, BlockPos origin) {
		double dx = origin.getX() + 8 - camera.x;
		double dz = origin.getZ() + 8 - camera.z;
		double d2 = dx * dx + dz * dz;
		if (d2 < RING_ONE_DISTANCE_SQUARED) {
			return 0;
		}
		if (d2 < RING_TWO_DISTANCE_SQUARED) {
			return 1;
		}
		if (d2 < RING_THREE_DISTANCE_SQUARED) {
			return 2;
		}
		return 3;
	}

	private void writeVisibleFaces(UnitWriter output, PackMetadata metadata, long vertices, TerrainVertex format, BlockPos origin, float visibility, int[] faces, int[] merged, int mask, int statBase, int slot) {
		if (AbSwitch.is("skipPlants")) {
			mask &= ~(1 << FaceBuckets.UNASSIGNED);
		}
		int runStart = 0;
		int runEnd = 0;
		int position = 0;
		for (int bucket = 0; bucket < FaceBuckets.COUNT; bucket++) {
			int next = position + faces[bucket];
			if ((mask & 1 << bucket) != 0) {
				int kind = bucket == FaceBuckets.UNASSIGNED ? 1 : 0;
				quadStats[statBase + kind] += faces[bucket];
				mergeStats[statBase + kind] += merged == null ? faces[bucket] : merged[bucket];
				if (runEnd != position) {
					writeQuads(output, metadata, vertices, format, origin, visibility, runStart, runEnd, slot);
					runStart = position;
				}
				runEnd = next;
			}
			position = next;
		}
		writeQuads(output, metadata, vertices, format, origin, visibility, runStart, runEnd, slot);
	}

	private void writeQuads(UnitWriter output, PackMetadata metadata, long vertices, TerrainVertex format, BlockPos origin, float visibility, int start, int end, int slot) {
		for (int quad = start; quad < end; quad += TerrainShaderPatch.QUADS_PER_UNIT) {
			int count = Math.min(TerrainShaderPatch.QUADS_PER_UNIT, end - quad);
			long address = vertices + (long) quad * 4 * format.size;
			if (metadata == null) {
				output.unit(address, 0, origin.getX(), origin.getY(), origin.getZ(), visibility, count, INDEX_NONE, format.ordinal(), slot);
			} else {
				output.packUnit(address, 0, metadata.address(), metadata.stride(), quad, origin.getX(), origin.getY(), origin.getZ(), visibility, count,
					INDEX_NONE, format.ordinal() | PackTerrainUnits.PACK_FLAG, slot);
			}
		}
	}
}
