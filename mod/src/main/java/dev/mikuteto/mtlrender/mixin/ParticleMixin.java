package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.particle.ParticleLightCache;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Particle.class)
abstract class ParticleMixin {
	@Redirect(
		method = "getLightCoords",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/LightCoordsUtil;getLightCoords(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I")
	)
	private int mtlrender$cacheLight(BlockAndLightGetter level, BlockPos position) {
		boolean enabled = AbSwitch.is(AbSwitch.PARTICLE_LIGHT, Config.get().particles.light);
		return enabled ? ParticleLightCache.get(position.asLong(), () -> LightCoordsUtil.getLightCoords(level, position)) : LightCoordsUtil.getLightCoords(level, position);
	}
}
