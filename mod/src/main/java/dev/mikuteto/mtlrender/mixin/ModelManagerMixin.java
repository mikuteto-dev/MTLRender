package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.debug.AssetMemory;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.blockentity.VanillaBlockEntityBakeSet;
import dev.mikuteto.mtlrender.mixin.BlockModelSetAccess;
import dev.mikuteto.mtlrender.mixin.EntityModelSetMemoryAccess;
import dev.mikuteto.mtlrender.mixin.ModelManagerMemoryAccess;
import dev.mikuteto.mtlrender.mixin.TextureAtlasMemoryAccess;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pathtracing.BlockStateAlbedo;
import dev.mikuteto.mtlrender.terrain.RustModelTable;
import dev.mikuteto.mtlrender.gui.GuiMapAtlas;
import dev.mikuteto.mtlrender.gui.GuiRenderSnapshot;
import dev.mikuteto.mtlrender.entity.EntityGeometry;
import dev.mikuteto.mtlrender.particle.ParticleLightCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelManager.class)
abstract class ModelManagerMixin {
	@Inject(method = "apply", at = @At("TAIL"))
	private void mtlrender$exportModels(CallbackInfo ci) {
		ModelManager manager = (ModelManager) (Object) this;
		RustModelTable.export(manager);
		EntityGeometry.closeAll();
		ParticleLightCache.clear();
		GuiMapAtlas.invalidate();
		VanillaBlockEntityBakeSet.rebuild(manager);
		Minecraft.getInstance().levelExtractor.allChanged();
		BlockStateAlbedo.rebuild(manager);
		PackManager.get().reload();
		ModelManagerMemoryAccess access = (ModelManagerMemoryAccess) (Object) this;
		AssetMemory assets = AssetMemory.global();
		assets.resetAtlases();
		access.mtlrender$atlasManager().forEach((id, atlas) -> assets.inspectAtlas(atlas, Config.get().assetRelease));
		long bakedBlocks = access.mtlrender$bakedBlockModels() == null ? 0L : ((BlockModelSetAccess) (Object) access.mtlrender$bakedBlockModels()).mtlrender$models().size();
		long fluids = access.mtlrender$fluidModels() == null ? 0L : ((dev.mikuteto.mtlrender.mixin.FluidStateModelSetAccess) (Object) access.mtlrender$fluidModels()).mtlrender$models().size();
		long entities = ((EntityModelSetMemoryAccess) (Object) access.mtlrender$entityModels()).mtlrender$roots().size();
		assets.models(access.mtlrender$itemModels().size(), bakedBlocks, fluids, entities);
		GuiRenderSnapshot.resourcesApplied();
	}
}
