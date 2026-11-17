package dev.mikuteto.mtlrender.mixin;

import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MapRenderState.class)
abstract class MapRenderStateMixin implements MapRenderStateAccess {
	@Unique private Identifier mtlrender$atlasTexture;
	@Unique private float mtlrender$atlasU0;
	@Unique private float mtlrender$atlasU1;
	@Unique private float mtlrender$atlasV0;
	@Unique private float mtlrender$atlasV1;
	@Unique private long mtlrender$pixelVersion;
	@Unique private long mtlrender$decorationVersion;
	@Unique private long mtlrender$resourceGeneration;

	@Override
	public void mtlrender$setAtlas(final Identifier texture, final float u0, final float u1, final float v0, final float v1) {
		this.mtlrender$atlasTexture = texture;
		this.mtlrender$atlasU0 = u0;
		this.mtlrender$atlasU1 = u1;
		this.mtlrender$atlasV0 = v0;
		this.mtlrender$atlasV1 = v1;
	}

	@Override
	public void mtlrender$setVersions(final long pixels, final long decorations, final long resourceGeneration) {
		this.mtlrender$pixelVersion = pixels;
		this.mtlrender$decorationVersion = decorations;
		this.mtlrender$resourceGeneration = resourceGeneration;
	}

	@Override public boolean mtlrender$hasAtlas() { return this.mtlrender$atlasTexture != null; }
	@Override public Identifier mtlrender$atlasTexture() { return this.mtlrender$atlasTexture; }
	@Override public float mtlrender$atlasU0() { return this.mtlrender$atlasU0; }
	@Override public float mtlrender$atlasU1() { return this.mtlrender$atlasU1; }
	@Override public float mtlrender$atlasV0() { return this.mtlrender$atlasV0; }
	@Override public float mtlrender$atlasV1() { return this.mtlrender$atlasV1; }
}
