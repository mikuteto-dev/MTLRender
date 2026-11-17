package dev.mikuteto.mtlrender.culling;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

public final class Visibility {
	public enum Pass {
		MAIN_CAMERA,
		SHADOW
	}

	private Visibility() {
	}

	public static int exceptionFlags(EntityRenderState state, boolean cameraEntity, boolean spectator, boolean vehicleOrPassenger) {
		int flags = 0;
		if (state.appearsGlowing()) {
			flags |= FeatureInstanceStream.FLAG_OUTLINE | FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		}
		if (state.nameTag != null) {
			flags |= FeatureInstanceStream.FLAG_NAME_TAG | FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		}
		if (cameraEntity) {
			flags |= FeatureInstanceStream.FLAG_CAMERA | FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		}
		if (vehicleOrPassenger) {
			flags |= FeatureInstanceStream.FLAG_VEHICLE | FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		}
		if (spectator) {
			flags |= FeatureInstanceStream.FLAG_SPECTATOR | FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		}
		if (state.shadowRadius > 0.0F || !state.shadowPieces.isEmpty()) {
			flags |= FeatureInstanceStream.FLAG_SHADOW | FeatureInstanceStream.FLAG_FORCE_VISIBLE;
		}
		return flags;
	}

	public static boolean appliesMask(Pass pass) {
		return pass == Pass.MAIN_CAMERA;
	}

	public static boolean forceVisible(Entity entity) {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.player != null && minecraft.player.isSpectator()
			|| entity == minecraft.gameRenderer.mainCamera().entity()
			|| entity.isVehicle() || entity.isPassenger() || entity.isCurrentlyGlowing()
			|| entity.shouldShowName() || entity.hasCustomName()
			|| entity instanceof Leashable leashable && leashable.getLeashHolder() != null;
	}

	public static boolean entityCandidate(Entity entity, Frustum frustum, double camX, double camY, double camZ) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!enabled(Config.get().entities.hizOcclusion, "entityCull")) {
			return true;
		}
		if (minecraft.level == null || minecraft.levelRenderer == null || minecraft.gameRenderer.mainCamera() == null) {
			return true;
		}
		if (forceVisible(entity)) {
			return true;
		}
		EntityRenderDispatcher dispatcher = minecraft.levelRenderer.entityRenderDispatcher();
		if (!dispatcher.shouldRender(entity, frustum, camX, camY, camZ)
			&& !(minecraft.player != null && entity.hasIndirectPassenger(minecraft.player))) {
			return false;
		}
		AABB bounds = entity.getBoundingBox();
		return sectionVisible(bounds);
	}

	public static boolean blockEntityCandidate(BlockEntity blockEntity, boolean globallyRendered) {
		if (globallyRendered || !enabled(Config.get().blockEntities.hizOcclusion, "blockEntityCull")) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.levelRenderer == null) {
			return true;
		}
		AABB bounds = new AABB(blockEntity.getBlockPos()).inflate(2.0);
		return sectionVisible(bounds);
	}

	private static boolean sectionVisible(AABB bounds) {
		if (bounds.hasNaN() || !finite(bounds.minX) || !finite(bounds.minY) || !finite(bounds.minZ)
			|| !finite(bounds.maxX) || !finite(bounds.maxY) || !finite(bounds.maxZ)
			|| bounds.minX > bounds.maxX || bounds.minY > bounds.maxY || bounds.minZ > bounds.maxZ
			|| Math.abs(bounds.minX) > Integer.MAX_VALUE - 16.0 || Math.abs(bounds.minY) > Integer.MAX_VALUE - 16.0
			|| Math.abs(bounds.minZ) > Integer.MAX_VALUE - 16.0 || Math.abs(bounds.maxX) > Integer.MAX_VALUE - 16.0
			|| Math.abs(bounds.maxY) > Integer.MAX_VALUE - 16.0 || Math.abs(bounds.maxZ) > Integer.MAX_VALUE - 16.0) {
			return true;
		}
		int minX = Math.floorDiv((int) Math.floor(bounds.minX), 16);
		int minY = Math.floorDiv((int) Math.floor(bounds.minY), 16);
		int minZ = Math.floorDiv((int) Math.floor(bounds.minZ), 16);
		int maxX = Math.floorDiv((int) Math.floor(bounds.maxX), 16);
		int maxY = Math.floorDiv((int) Math.floor(bounds.maxY), 16);
		int maxZ = Math.floorDiv((int) Math.floor(bounds.maxZ), 16);
		if ((long) maxX - minX > 128L || (long) maxY - minY > 128L || (long) maxZ - minZ > 128L) {
			return true;
		}
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					if (Minecraft.getInstance().levelRenderer.isSectionCompiledAndVisible(new BlockPos(x << 4, y << 4, z << 4))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean finite(double value) {
		return Double.isFinite(value);
	}

	public static boolean entity(EntityRenderState state) {
		if (!enabled(Config.get().entities.raycast, "entityCull")) {
			return true;
		}
		if ((exceptionFlags(state, false, false, false) & FeatureInstanceStream.FLAG_FORCE_VISIBLE) != 0 || state.leashStates != null) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.gameRenderer.mainCamera() == null) {
			return true;
		}
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 from = camera.position();
		float halfWidth = Math.max(state.boundingBoxWidth * 0.5F, 0.05F);
		float height = Math.max(state.boundingBoxHeight, 0.1F);
		Vec3[] points = {
			new Vec3(state.x, state.y, state.z),
			new Vec3(state.x - halfWidth, state.y, state.z - halfWidth),
			new Vec3(state.x + halfWidth, state.y, state.z - halfWidth),
			new Vec3(state.x - halfWidth, state.y, state.z + halfWidth),
			new Vec3(state.x + halfWidth, state.y, state.z + halfWidth),
			new Vec3(state.x, state.y + height, state.z)
		};
		for (Vec3 point : points) {
			if (clear(from, point)) {
				return true;
			}
		}
		return false;
	}

	public static boolean blockEntity(BlockEntityRenderState state) {
		if (!enabled(Config.get().blockEntities.raycast, "blockEntityCull")) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.gameRenderer.mainCamera() == null) {
			return true;
		}
		Vec3 from = minecraft.gameRenderer.mainCamera().position();
		BlockPos pos = state.blockPos;
		Vec3 center = Vec3.atCenterOf(pos);
		return clear(from, center)
			|| clear(from, center.add(0.49, 0.49, 0.49))
			|| clear(from, center.add(-0.49, -0.49, -0.49));
	}

	public static boolean weather(WeatherEffectRenderer.ColumnInstance column, Vec3 camera, int radius) {
		if (!enabled(Config.get().weather.frustum, "weatherCull")) {
			return true;
		}
		double dx = column.x() + 0.5 - camera.x;
		double dz = column.z() + 0.5 - camera.z;
		return dx * dx + dz * dz <= (double) radius * radius;
	}

	private static boolean clear(Vec3 from, Vec3 to) {
		BlockHitResult result = Minecraft.getInstance().level.clip(new ClipContext(
			from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Minecraft.getInstance().player
		));
		if (result.getType() == HitResult.Type.MISS) {
			return true;
		}
		return result.getLocation().distanceToSqr(from) + 0.01 >= to.distanceToSqr(from);
	}

	private static boolean enabled(boolean configured, String name) {
		return name.equals(AbSwitch.NAME) ? AbSwitch.b() : configured;
	}
}
