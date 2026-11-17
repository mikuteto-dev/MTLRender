package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakeMetadata;
import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakePublication;
import dev.mikuteto.mtlrender.pathtracing.VoxelBrickSet;
import dev.mikuteto.mtlrender.pathtracing.VoxelBricks;
import dev.mikuteto.mtlrender.pathtracing.VoxelSectionStateCapture;
import dev.mikuteto.mtlrender.pathtracing.VoxelStateAccess;
import dev.mikuteto.mtlrender.terrain.DirectUberBuffer;
import dev.mikuteto.mtlrender.terrain.MeshEntries;
import dev.mikuteto.mtlrender.terrain.MeshOwnership;
import dev.mikuteto.mtlrender.terrain.PackTerrainJavaMesh;
import dev.mikuteto.mtlrender.terrain.SectionCpuRetention;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import com.mojang.blaze3d.vertex.MeshData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshMixin implements MeshEntries, VoxelStateAccess, StaticBlockEntityBakeMetadataAccess {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void mtlrender$captureState(
		TranslucencyPointOfView translucencyPointOfView,
		SectionCompiler.Results results,
		CallbackInfo info
	) {
		mtlrender$stateIds = VoxelSectionStateCapture.consume(results);
		if (mtlrender$stateIds != null) {
			SectionCpuRetention.retainStateIds();
		}
		int callbacks = 0;
		for (MeshData mesh : results.renderedLayers.values()) {
			callbacks++;
			if (mesh.indexBuffer() != null) {
				callbacks++;
			}
		}
		mtlrender$ownership = new MeshOwnership.Lease(MeshOwnership.context(), callbacks);
		mtlrender$staticBlockEntityBakeMetadata = ((SectionCompilerResultsAccess) (Object) results).mtlrender$staticBlockEntityBakeMetadata();
		mtlrender$staticBlockEntityBakePublication = mtlrender$staticBlockEntityBakeMetadata.publication().orElse(null);
		SectionCompilerResultsAccess resultAccess = (SectionCompilerResultsAccess) (Object) results;
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			Object metadata = resultAccess.mtlrender$packTerrainMetadata(layer);
			if (metadata instanceof PackTerrainJavaMesh pack) {
				mtlrender$setPackTerrainMetadata(layer.ordinal(), pack);
			}
		}
	}

	@Unique
	private int[] mtlrender$stateIds;

	@Unique
	private MeshOwnership.Lease mtlrender$ownership;

	@Unique
	private StaticBlockEntityBakeMetadata mtlrender$staticBlockEntityBakeMetadata = StaticBlockEntityBakeMetadata.empty();

	@Unique
	private StaticBlockEntityBakePublication mtlrender$staticBlockEntityBakePublication;

	@Unique
	private final DirectUberBuffer.Entry[] mtlrender$entries = new DirectUberBuffer.Entry[3 * ChunkSectionLayer.values().length];

	@Unique
	private final PackTerrainJavaMesh[] mtlrender$packMetadata = new PackTerrainJavaMesh[ChunkSectionLayer.values().length];

	@Unique
	private volatile VoxelBrickSet mtlrender$voxelBricks;

	@Unique
	private int mtlrender$section = -1;

	@Override
	public StaticBlockEntityBakePublication mtlrender$staticBlockEntityBakePublication() {
		return mtlrender$staticBlockEntityBakePublication;
	}

	@Override
	public StaticBlockEntityBakeMetadata mtlrender$staticBlockEntityBakeMetadata() {
		return mtlrender$staticBlockEntityBakeMetadata;
	}

	@Override
	public int[] mtlrender$stateIdsXPlus16YPlus256Z() {
		return this.mtlrender$stateIds;
	}

	@Override
	public void mtlrender$setStateIdsXPlus16YPlus256Z(int[] stateIds) {
		if (this.mtlrender$stateIds != null) {
			SectionCpuRetention.releaseStateIds();
		}
		this.mtlrender$stateIds = stateIds;
		if (stateIds != null) {
			SectionCpuRetention.retainStateIds();
		}
	}

	@Override
	public DirectUberBuffer.Entry mtlrender$entry(int layer) {
		return layer >= 0 && layer < this.mtlrender$entries.length ? this.mtlrender$entries[layer] : null;
	}

	@Override
	public void mtlrender$setEntry(int layer, DirectUberBuffer.Entry entry) {
		if (layer >= 0 && layer < this.mtlrender$entries.length) {
			this.mtlrender$entries[layer] = entry;
		}
	}

	@Override
	public PackTerrainJavaMesh mtlrender$packTerrainMetadata(int layer) {
		return layer >= 0 && layer < this.mtlrender$packMetadata.length ? this.mtlrender$packMetadata[layer] : null;
	}

	@Override
	public void mtlrender$setPackTerrainMetadata(int layer, PackTerrainJavaMesh metadata) {
		if (layer >= 0 && layer < this.mtlrender$packMetadata.length) {
			this.mtlrender$packMetadata[layer] = metadata;
		}
	}

	@Override
	public VoxelBrickSet mtlrender$voxelBricks() {
		return this.mtlrender$voxelBricks;
	}

	@Override
	public void mtlrender$setVoxelBricks(VoxelBrickSet bricks) {
		this.mtlrender$voxelBricks = bricks;
	}

	@Override
	public boolean mtlrender$acceptUploadCallback(boolean sortedIndexBuffer) {
		if (this.mtlrender$ownership == null || !this.mtlrender$ownership.acceptCallback(sortedIndexBuffer)) {
			return false;
		}
		if (!sortedIndexBuffer && this.mtlrender$ownership.uploadComplete() && !SectionCpuRetention.required()) {
			mtlrender$releaseStateIds();
		}
		return true;
	}

	@Override
	public boolean mtlrender$isCurrent() {
		return this.mtlrender$ownership == null || this.mtlrender$ownership.token() == null
			|| MeshOwnership.current(this.mtlrender$ownership.token());
	}

	@Override
	public int mtlrender$section() {
		return this.mtlrender$section;
	}

	@Override
	public void mtlrender$setSection(int index) {
		this.mtlrender$section = index;
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mtlrender$closeOwnership(CallbackInfo info) {
		if (this.mtlrender$ownership != null) {
			this.mtlrender$ownership.cancel();
		}
		java.util.Arrays.fill(this.mtlrender$entries, null);
		java.util.Arrays.fill(this.mtlrender$packMetadata, null);
		mtlrender$releaseStateIds();
		if (!SectionCpuRetention.required()) {
			VoxelBricks.release(this);
		}
	}

	@Unique
	private void mtlrender$releaseStateIds() {
		if (this.mtlrender$stateIds != null) {
			this.mtlrender$stateIds = null;
			SectionCpuRetention.releaseStateIds();
		}
	}
}
