package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MapRenderState.class)
public interface MapRenderStateAccess {
	void mtlrender$setAtlas(Identifier texture, float u0, float u1, float v0, float v1);
	void mtlrender$setVersions(long pixels, long decorations, long resourceGeneration);
	boolean mtlrender$hasAtlas();
	Identifier mtlrender$atlasTexture();
	float mtlrender$atlasU0();
	float mtlrender$atlasU1();
	float mtlrender$atlasV0();
	float mtlrender$atlasV1();
}
