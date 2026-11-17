package dev.mikuteto.mtlrender.pack.uniform;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import dev.mikuteto.mtlrender.pack.shadow.ShadowFrameSnapshot;
import dev.mikuteto.mtlrender.pack.shadow.ShadowMatrices;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import java.time.LocalDateTime;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

public final class GameState extends UniformValues {
	private double previousX;
	private double previousY;
	private double previousZ;
	private boolean hasCamera;
	private long frameCounter;
	private final Matrix4f celestialMatrix = new Matrix4f();
	private final Vector4f celestialPosition = new Vector4f();
	private final Matrix4f previousModelView = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();
	private final Matrix4f inverse = new Matrix4f();
	private boolean hasMatrices;
	private long wallSecond = -1L;
	private LocalDateTime dateTime;

	public GameState() {
		super(Builtins.types());
	}

	public void collect(Minecraft client, Camera camera, LevelRenderState renderState, DeltaTracker deltaTracker,
		ShadowFrameSnapshot shadowFrame) {
		collect(client, camera, renderState, deltaTracker);
		publishShadow(shadowFrame);
	}

	public void collect(Minecraft client, Camera camera, LevelRenderState renderState, DeltaTracker deltaTracker) {
		for (Value value : values().values()) {
			value.zero();
		}
		float partial = deltaTracker == null ? 0.0F : deltaTracker.getGameTimeDeltaPartialTick(true);
		ClientLevel level = client.level;
		CameraRenderState cameraState = renderState == null ? null : renderState.cameraRenderState;
		Vec3 position = camera == null ? Vec3.ZERO : camera.position();
		double oldX = previousX;
		double oldY = previousY;
		double oldZ = previousZ;
		boolean hadCamera = hasCamera;
		setVec3("cameraPosition", position.x, position.y, position.z);
		if (hadCamera) {
			setVec3("previousCameraPosition", oldX, oldY, oldZ);
		} else {
			setVec3("previousCameraPosition", position.x, position.y, position.z);
		}
		setInt3("previousCameraPositionInt", floor(hadCamera ? oldX : position.x), floor(hadCamera ? oldY : position.y), floor(hadCamera ? oldZ : position.z));
		setVec3("previousCameraPositionFract", fract(hadCamera ? oldX : position.x), fract(hadCamera ? oldY : position.y), fract(hadCamera ? oldZ : position.z));
		hasCamera = true;
		previousX = position.x;
		previousY = position.y;
		previousZ = position.z;
		setFloat("near", 0.05);
		setFloat("far", cameraState == null ? client.options.getEffectiveRenderDistance() * 16.0 : cameraState.depthFar);
		setFloat("eyeAltitude", position.y);
		setInt3("cameraPositionInt", floor(position.x), floor(position.y), floor(position.z));
		setVec3("cameraPositionFract", fract(position.x), fract(position.y), fract(position.z));
		setFloat("viewWidth", client.getWindow().getWidth());
		setFloat("viewHeight", client.getWindow().getHeight());
		setFloat("aspectRatio", (double) client.getWindow().getWidth() / Math.max(1, client.getWindow().getHeight()));
		setVec2("iris_ScreenSize", client.getWindow().getWidth(), client.getWindow().getHeight());
		setFloat("pi", Math.PI);
		frameCounter++;
		setInt("frameCounter", frameCounter);
		long currentSecond = System.currentTimeMillis() / 1000L;
		if (currentSecond != wallSecond) {
			wallSecond = currentSecond;
			dateTime = LocalDateTime.now();
		}
		if (dateTime != null) {
			setInt3("currentDate", dateTime.getYear(), dateTime.getMonthValue(), dateTime.getDayOfMonth());
			setInt3("currentTime", dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
			int elapsed = (dateTime.getDayOfYear() - 1) * 86400 + dateTime.getHour() * 3600 + dateTime.getMinute() * 60 + dateTime.getSecond();
			setInt2("currentYearTime", elapsed, dateTime.toLocalDate().lengthOfYear() * 86400 - elapsed);
		}
		setFloat("frameTime", deltaTracker == null ? 0.0 : deltaTracker.getRealtimeDeltaTicks() / 20.0);
		setFloat("frameTimeCounter", frameCounter * (deltaTracker == null ? 0.0 : deltaTracker.getRealtimeDeltaTicks()) / 20.0);
		setInt("worldTime", level == null ? 0 : (int) (level.getDefaultClockTime() % 24000L));
		setInt("worldDay", level == null ? 0 : (int) (level.getDefaultClockTime() / 24000L));
		setInt("moonPhase", 0);
		if (level != null) {
			setFloat("rainStrength", level.getRainLevel(partial));
			if (camera != null) {
				int moonPhase = camera.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, partial).index();
				setInt("moonPhase", moonPhase);
				int sky = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partial);
				setVec3("skyColor", ((sky >> 16) & 255) / 255.0, ((sky >> 8) & 255) / 255.0, (sky & 255) / 255.0);
				setInt2("eyeBrightness", level.getBrightness(LightLayer.BLOCK, camera.blockPosition()) * 16, level.getBrightness(LightLayer.SKY, camera.blockPosition()) * 16);
			}
			net.minecraft.core.BlockPos biomePosition = camera == null ? net.minecraft.core.BlockPos.ZERO : camera.blockPosition();
			setFloat("temperature", level.getBiome(biomePosition).value().getBaseTemperature());
			setInt("biome_precipitation", switch (level.getBiome(biomePosition).value().getPrecipitationAt(biomePosition, level.getSeaLevel())) {
				case NONE -> 0;
				case RAIN -> 1;
				case SNOW -> 2;
			});
			setFloat("thunderStrength", level.getThunderLevel(partial));
			setInt("bedrockLevel", level.dimensionType().minY());
			setInt("heightLimit", level.dimensionType().height());
			setInt("logicalHeightLimit", level.dimensionType().logicalHeight());
			setBool("hasCeiling", level.dimensionType().hasCeiling());
			setBool("hasSkylight", level.dimensionType().hasSkyLight());
			setFloat("ambientLight", level.dimensionType().ambientLight());
			setInt("seaLevel", level.getSeaLevel());
			setLongFloat("cloudTime", level.getGameTime());
		}
		Options options = client.options;
		setBool("hideGUI", false);
		setBool("isRightHanded", options.mainHand().get() == net.minecraft.world.entity.HumanoidArm.RIGHT);
		setFloat("screenBrightness", options.gamma().get());
		setInt("textureFilteringMode", switch (options.textureFiltering().get()) {
			case NONE -> 0;
			case RGSS -> 1;
			case ANISOTROPIC -> 2;
		});
		setInt("anisotropicFiltering", options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC ? options.maxAnisotropyValue() : 0);
		if (camera != null) {
			setInt("isEyeInWater", camera.getFluidInCamera().ordinal());
		}
		if (cameraState != null) {
			setInt("fogMode", cameraState.fogType.ordinal());
			setFloat("fogStart", cameraState.fogData.environmentalStart);
			setFloat("fogEnd", cameraState.fogData.environmentalEnd);
			setFloat("fogDensity", cameraState.fogData.renderDistanceEnd);
			setVec3("fogColor", cameraState.fogData.color.x, cameraState.fogData.color.y, cameraState.fogData.color.z);
			setVec4("iris_FogColor", cameraState.fogData.color.x, cameraState.fogData.color.y, cameraState.fogData.color.z, cameraState.fogData.color.w);
			setCameraMatrices(cameraState.projectionMatrix, cameraState.viewRotationMatrix);
			if (camera != null) {
				float sunAngle = camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partial);
				float moonAngle = camera.attributeProbe().getValue(EnvironmentAttributes.MOON_ANGLE, partial);
				setCelestialValues(cameraState.viewRotationMatrix, sunAngle, moonAngle);
				setUpPosition(cameraState.viewRotationMatrix);
			}
		}
		LocalPlayer player = client.player;
		if (player != null) {
			setBool("is_sneaking", player.isCrouching());
			setBool("is_sprinting", player.isSprinting());
			setBool("is_hurt", player.hurtTime > 0);
			setBool("is_invisible", player.isInvisible());
			setBool("is_burning", player.isOnFire());
			setBool("is_on_ground", player.onGround());
			setBool("feetInWater", player.isInWater());
			setBool("inSwimmingAnimation", player.isSwimming());
			setBool("isRiding", player.isPassenger());
			setBool("isElytraFlying", player.isFallFlying());
			setFloat("currentPlayerHealth", player.getHealth());
			setFloat("maxPlayerHealth", player.getMaxHealth());
			setFloat("currentPlayerHunger", player.getFoodData().getFoodLevel());
			setFloat("maxPlayerHunger", 20.0);
			setFloat("currentPlayerArmor", player.getArmorValue());
			setFloat("maxPlayerArmor", 50.0);
			setFloat("currentPlayerAir", player.getAirSupply());
			setFloat("maxPlayerAir", player.getMaxAirSupply());
			setBool("firstPersonCamera", options.getCameraType().isFirstPerson());
			setVec3("playerLookVector", player.getViewVector(partial).x, player.getViewVector(partial).y, player.getViewVector(partial).z);
		}
		setBool("isSpectator", client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SPECTATOR);

	}

	public void publishShadow(ShadowFrameSnapshot snapshot) {
		ShadowFrameSnapshot frame = java.util.Objects.requireNonNull(snapshot, "shadow frame");
		if (!frame.enabled()) {
			clearShadow();
			return;
		}
		ShadowMatrices matrices = frame.plan().matrices();
		setMatrix4("shadowModelView", matrices.modelView());
		setMatrix4("shadowModelViewInverse", matrices.modelViewInverse());
		setMatrix4("shadowProjection", matrices.projection());
		setMatrix4("shadowProjectionInverse", matrices.projectionInverse());
		setMatrix4("iris_ShadowModelViewMatrixInverse", matrices.modelViewInverse());
		setMatrix4("iris_ShadowProjectionMatrixInverse", matrices.projectionInverse());
	}

	void setCelestialValues(Matrix4fc view, float sunAngle, float moonAngle) {
		float normalizedSunAngle = normalizeAngle(sunAngle + 90.0F);
		setFloat("sunAngle", normalizedSunAngle / 360.0);
		setFloat("shadowAngle", normalizedSunAngle / 360.0);
		setCelestial("sunPosition", view, sunAngle, 100.0F);
		setCelestial("moonPosition", view, moonAngle, -100.0F);
		boolean day = normalizedSunAngle < 180.0F;
		String light = day ? "sunPosition" : "moonPosition";
		setVec3("shadowLightPosition", value(light, 0), value(light, 1), value(light, 2));
	}

	private double value(String name, int component) {
		return value(name).number(component);
	}

	public void setDrawValue(String name, Type type, double a, double b, double c, double d) {
		Value value = require(name, type);
		value.zero();
		value.setNumber(0, a);
		value.setNumber(1, b);
		value.setNumber(2, c);
		value.setNumber(3, d);
	}

	public void setDrawValue(String name, Type type, int value) {
		Value target = require(name, type);
		target.zero();
		target.setInteger(0, value);
	}

	private void setFloat(String name, double value) {
		Value target = require(name, Type.FLOAT);
		target.setNumber(0, value);
	}

	private void setLongFloat(String name, long value) {
		setFloat(name, value);
	}

	private void setInt(String name, long value) {
		Value target = require(name, Type.INT);
		target.setInteger(0, (int) value);
	}

	private void setInt2(String name, int x, int y) {
		Value target = require(name, Type.IVEC2);
		target.setInteger(0, x);
		target.setInteger(1, y);
	}

	private void setInt3(String name, int x, int y, int z) {
		Value target = require(name, Type.IVEC3);
		target.setInteger(0, x);
		target.setInteger(1, y);
		target.setInteger(2, z);
	}

	private void setVec2(String name, double x, double y) {
		Value target = require(name, Type.VEC2);
		target.setNumber(0, x);
		target.setNumber(1, y);
	}

	private void setVec3(String name, double x, double y, double z) {
		Value target = require(name, Type.VEC3);
		target.setNumber(0, x);
		target.setNumber(1, y);
		target.setNumber(2, z);
	}

	private void setVec4(String name, double x, double y, double z, double w) {
		Value target = require(name, Type.VEC4);
		target.setNumber(0, x);
		target.setNumber(1, y);
		target.setNumber(2, z);
		target.setNumber(3, w);
	}

	private void setCelestial(String name, Matrix4fc view, float angle, float height) {
		celestialMatrix.set(view).rotateY((float) -Math.PI / 2.0F).rotateX((float) Math.toRadians(angle));
		celestialPosition.set(0.0F, height, 0.0F, 1.0F);
		celestialMatrix.transform(celestialPosition);
		setVec3(name, celestialPosition.x, celestialPosition.y, celestialPosition.z);
	}

	private void setUpPosition(Matrix4fc view) {
		celestialPosition.set(0.0F, 100.0F, 0.0F, 0.0F);
		view.transform(celestialPosition);
		setVec3("upPosition", celestialPosition.x, celestialPosition.y, celestialPosition.z);
	}


	private static float normalizeAngle(float angle) {
		return (angle % 360.0F + 360.0F) % 360.0F;
	}

	void setCameraMatrices(Matrix4fc gameProjection, Matrix4fc modelView) {
		Matrix4f packProjection = DepthConvention.gameProjectionToPack(gameProjection);
		Matrix4f packInverse = DepthConvention.gameProjectionInverseToPack(inverse.set(gameProjection).invert());
		if (hasMatrices) {
			setMatrix4("gbufferPreviousProjection", previousProjection);
			setMatrix4("gbufferPreviousModelView", previousModelView);
		} else {
			setMatrix4("gbufferPreviousProjection", packProjection);
			setMatrix4("gbufferPreviousModelView", modelView);
		}
		setMatrix4("gbufferProjection", packProjection);
		setMatrix4("gbufferProjectionInverse", packInverse);
		setMatrix4("iris_ProjectionMatrix", packProjection);
		setMatrix4("iris_ProjMat", packProjection);
		setMatrix4("iris_DefaultProjectionMatrixInverse", packInverse);
		setMatrix4("gbufferModelView", modelView);
		setMatrix4("gbufferModelViewInverse", inverse.set(modelView).invert());
		previousProjection.set(packProjection);
		previousModelView.set(modelView);
		hasMatrices = true;
	}

	private void clearShadow() {
		Matrix4f zero = new Matrix4f().zero();
		setMatrix4("shadowModelView", zero);
		setMatrix4("shadowModelViewInverse", zero);
		setMatrix4("shadowProjection", zero);
		setMatrix4("shadowProjectionInverse", zero);
		setMatrix4("iris_ShadowModelViewMatrixInverse", zero);
		setMatrix4("iris_ShadowProjectionMatrixInverse", zero);
		setFloat("shadowAngle", 0.0);
		setVec3("shadowLightPosition", 0.0, 0.0, 0.0);
	}

	private void setMatrix4(String name, Matrix4fc matrix) {
		Value target = require(name, Type.MAT4);
		target.setNumber(0, matrix.m00());
		target.setNumber(1, matrix.m01());
		target.setNumber(2, matrix.m02());
		target.setNumber(3, matrix.m03());
		target.setNumber(4, matrix.m10());
		target.setNumber(5, matrix.m11());
		target.setNumber(6, matrix.m12());
		target.setNumber(7, matrix.m13());
		target.setNumber(8, matrix.m20());
		target.setNumber(9, matrix.m21());
		target.setNumber(10, matrix.m22());
		target.setNumber(11, matrix.m23());
		target.setNumber(12, matrix.m30());
		target.setNumber(13, matrix.m31());
		target.setNumber(14, matrix.m32());
		target.setNumber(15, matrix.m33());
	}

	private void setBool(String name, boolean value) {
		Value target = require(name, Type.BOOL);
		target.setBoolean(0, value);
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}

	private static double fract(double value) {
		return value - Math.floor(value);
	}
}
