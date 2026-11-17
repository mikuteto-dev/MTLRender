package dev.mikuteto.mtlrender.pack.uniform;

import java.util.LinkedHashMap;
import java.util.Map;

final class Builtins {
	private Builtins() {
	}

	static Map<String, Type> types() {
		Map<String, Type> result = new LinkedHashMap<>();
		add(result, Type.FLOAT, "near", "far", "eyeAltitude", "sunAngle", "shadowAngle", "fogDensity", "fogStart", "fogEnd", "viewHeight", "viewWidth", "aspectRatio", "rainfall", "temperature", "frameTime", "frameTimeCounter", "screenBrightness", "blindness", "darknessFactor", "darknessLightFactor", "nightVision", "pi", "playerMood", "constantMood", "rainStrength", "wetness", "dhFarPlane", "dhNearPlane", "chunkFadeTimeInv", "thunderStrength", "currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger", "maxPlayerHunger", "endFlashIntensity", "previousEndFlashIntensity", "currentPlayerArmor", "maxPlayerArmor", "currentPlayerAir", "maxPlayerAir", "cloudHeight", "ambientLight", "BiomeTemp", "timeAngle", "timeBrightness", "moonBrightness", "shadowFade", "rainStrengthS", "rainStrengthShiningStars", "rainStrengthS2", "blindFactor", "isDry", "isRainy", "isSnowy", "isEyeInCave", "velocity", "starter", "frameTimeSmooth", "eyeBrightnessM", "rainFactor", "day", "night", "dawnDusk", "shdFade", "isPrecipitationRain", "touchmybody", "sneakSmooth", "burningSmooth", "effectStrength", "iris_FogStart", "iris_FogEnd", "iris_FogDensity", "iris_currentAlphaTest", "alphaTestRef", "iris_GlintAlpha", "iris_ModelScale", "iris_TextureScale");
		add(result, Type.INT, "biome", "biome_category", "biome_precipitation", "fogMode", "fogShape", "isEyeInWater", "worldTime", "worldDay", "moonPhase", "frameCounter", "gtextureId", "textureReloadCount", "renderStage", "entityId", "blockEntityId", "currentRenderedItemId", "anisotropicFiltering", "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2", "vehicleId", "currentSelectedBlockId", "bedrockLevel", "heightLimit", "logicalHeightLimit", "currentColorSpace", "textureFilteringMode", "seaLevel", "dhRenderDistance");
		add(result, Type.BOOL, "hideGUI", "isRightHanded", "is_sneaking", "is_sprinting", "is_hurt", "is_invisible", "is_burning", "is_on_ground", "feetInWater", "inSwimmingAnimation", "isRiding", "isElytraFlying", "vehicleInWater", "heavyFog", "firstPersonCamera", "isSpectator", "hasCeiling", "hasSkylight");
		add(result, Type.IVEC2, "atlasSize", "gtextureSize", "eyeBrightness", "eyeBrightnessSmooth", "currentYearTime");
		add(result, Type.IVEC3, "cameraPositionInt", "previousCameraPositionInt", "currentDate", "currentTime");
		add(result, Type.IVEC4, "blendFunc");
		add(result, Type.VEC2, "iris_ScreenSize");
		add(result, Type.VEC3, "u_ModelScale", "cameraPosition", "previousCameraPosition", "cameraPositionFract", "previousCameraPositionFract", "sunPosition", "moonPosition", "shadowLightPosition", "endFlashPosition", "upPosition", "fogColor", "skyColor", "heldBlockLightColor", "heldBlockLightColor2", "vehicleLookVector", "relativeVehiclePosition", "currentSelectedBlockPos", "eyePosition", "relativeEyePosition", "playerLookVector", "playerBodyVector", "iris_CameraTranslation", "u_RegionOffset", "iris_ModelOffset");
		add(result, Type.VEC4, "entityColor", "lightningBoltPosition", "iris_ColorModulator", "iris_FogColor");
		add(result, Type.MAT3, "iris_DefaultNormalMat", "iris_NormalMatrix", "iris_NormalMat");
		add(result, Type.MAT4, "gbufferModelView", "gbufferModelViewInverse", "gbufferPreviousModelView", "gbufferProjection", "gbufferProjectionInverse", "gbufferPreviousProjection", "dhModelView", "dhModelViewInverse", "dhPreviousModelView", "dhProjection", "dhProjectionInverse", "dhPreviousProjection", "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse", "iris_DefaultProjectionMatrixInverse", "iris_DefaultModelViewMatrixInverse", "iris_ShadowModelViewMatrixInverse", "iris_ShadowProjectionMatrixInverse", "iris_ModelViewMatrix", "u_ModelViewProjectionMatrix", "iris_ProjectionMatrix", "iris_ProjMat", "iris_ModelViewMat", "iris_TextureMat");
		return result;
	}

	private static void add(Map<String, Type> map, Type type, String... names) {
		for (String name : names) {
			map.put(name, type);
		}
	}
}
