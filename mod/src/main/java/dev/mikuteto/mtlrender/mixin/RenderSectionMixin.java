package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.blockentity.StaticBlockEntityBakePublication;
import dev.mikuteto.mtlrender.blockentity.VanillaBlockEntityBakeSet;
import dev.mikuteto.mtlrender.terrain.DirectUberBuffer;
import dev.mikuteto.mtlrender.terrain.MeshEntries;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
abstract class RenderSectionMixin {
	@Shadow
	@Final
	public int index;

	@Shadow
	public abstract long getSectionNode();

	@Inject(method = "setSectionMesh", at = @At("HEAD"), cancellable = true)
	private void mtlrender$guardSectionMesh(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
		if (sectionMesh instanceof MeshEntries entries && (!entries.mtlrender$isCurrent()
			|| !currentBakeGeneration(entries))) {
			DirectUberBuffer.discard(sectionMesh);
			sectionMesh.close();
			cir.setReturnValue(CompiledSectionMesh.UNCOMPILED);
		}
	}

	@Inject(method = "setSectionMesh", at = @At("TAIL"))
	private void mtlrender$setSectionMesh(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
		if (sectionMesh instanceof MeshEntries entries) {
			entries.mtlrender$setSection(this.index);
		}
		SectionMesh oldMesh = cir.getReturnValue();
		if (oldMesh instanceof MeshEntries oldEntries) {
			VanillaBlockEntityBakeSet.registry().remove(getSectionNode(), oldEntries.mtlrender$staticBlockEntityBakePublication());
		}
		if (sectionMesh instanceof MeshEntries entries) {
			StaticBlockEntityBakePublication publication = entries.mtlrender$staticBlockEntityBakePublication();
			if (publication != null) {
				VanillaBlockEntityBakeSet.registry().tryPublish(publication);
			}
		}
		TerrainRenderer.get().markDirty(this.index);
	}

	private static boolean currentBakeGeneration(MeshEntries entries) {
		StaticBlockEntityBakePublication publication = entries.mtlrender$staticBlockEntityBakePublication();
		return publication == null || VanillaBlockEntityBakeSet.registry().isCurrent(publication);
	}

	@Inject(method = "vertexBufferUploadCallback", at = @At("HEAD"), cancellable = true)
	private void mtlrender$vertexUpload(CompiledSectionMesh sectionMesh, ChunkSectionLayer layer, CallbackInfo ci) {
		if (sectionMesh instanceof MeshEntries entries && !entries.mtlrender$acceptUploadCallback(false)) {
			DirectUberBuffer.discard(sectionMesh);
			sectionMesh.close();
			ci.cancel();
		}
	}

	@Inject(method = "indexBufferUploadCallback", at = @At("HEAD"), cancellable = true)
	private void mtlrender$indexUpload(CompiledSectionMesh sectionMesh, ChunkSectionLayer layer, boolean sortedIndexBuffer, CallbackInfo ci) {
		if (sectionMesh instanceof MeshEntries entries && !entries.mtlrender$acceptUploadCallback(sortedIndexBuffer)) {
			DirectUberBuffer.discard(sectionMesh);
			sectionMesh.close();
			ci.cancel();
		}
	}

	@Inject(method = "resortTransparency", at = @At("HEAD"), cancellable = true)
	private void mtlrender$resortTransparency(CallbackInfo ci) {
		if (TerrainRenderer.get().translucentSortManaged()) {
			ci.cancel();
		}
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void mtlrender$reset(CallbackInfo ci) {
		VanillaBlockEntityBakeSet.registry().remove(getSectionNode(), null);
		TerrainRenderer.get().markDirty(this.index);
	}
}
