package dev.mikuteto.mtlrender.debug;

import java.util.List;
import java.util.Optional;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public final class BenchScenes {
	public static final int MANIFEST_VERSION = 1;

	private static final List<BenchScene> DEVELOPMENT = List.of(
		scene("dev-plains", BenchScene.SetKind.DEVELOPMENT, 20261020L, "mtlr-dev-plains", Biomes.PLAINS, WorldPresets.NORMAL, BenchScene.TerrainProfile.NORMAL, "dev-plains-path", new BenchScene.Spawn(0.5, 96.0, 0.5, 45.0F, 10.0F), BenchScene.Weather.CLEAR, 6_000, "horizon", "village-edge"),
		scene("dev-forest", BenchScene.SetKind.DEVELOPMENT, 20261021L, "mtlr-dev-forest", Biomes.FOREST, WorldPresets.LARGE_BIOMES, BenchScene.TerrainProfile.LARGE_BIOMES, "dev-forest-path", new BenchScene.Spawn(32.5, 104.0, -20.5, 135.0F, 12.0F), BenchScene.Weather.RAIN, 9_000, "canopy", "ground-cover"),
		scene("dev-windswept", BenchScene.SetKind.DEVELOPMENT, 20261022L, "mtlr-dev-windswept", Biomes.WINDSWEPT_HILLS, WorldPresets.AMPLIFIED, BenchScene.TerrainProfile.AMPLIFIED, "dev-windswept-path", new BenchScene.Spawn(-48.5, 148.0, 24.5, -30.0F, 18.0F), BenchScene.Weather.CLEAR, 13_000, "ridge", "valley"),
		scene("dev-desert", BenchScene.SetKind.DEVELOPMENT, 20261023L, "mtlr-dev-desert", Biomes.DESERT, WorldPresets.NORMAL, BenchScene.TerrainProfile.NORMAL, "dev-desert-path", new BenchScene.Spawn(80.5, 88.0, 40.5, -120.0F, 8.0F), BenchScene.Weather.CLEAR, 18_000, "dune", "structure-silhouette")
	);

	private static final List<BenchScene> HELD_OUT = List.of(
		scene("heldout-taiga", BenchScene.SetKind.HELD_OUT, 20261101L, "mtlr-heldout-taiga", Biomes.TAIGA, WorldPresets.NORMAL, BenchScene.TerrainProfile.NORMAL, "heldout-taiga-path", new BenchScene.Spawn(-72.5, 102.0, -56.5, 22.0F, 11.0F), BenchScene.Weather.CLEAR, 3_000, "snow-line", "spruce-gap"),
		scene("heldout-swamp", BenchScene.SetKind.HELD_OUT, 20261102L, "mtlr-heldout-swamp", Biomes.SWAMP, WorldPresets.LARGE_BIOMES, BenchScene.TerrainProfile.LARGE_BIOMES, "heldout-swamp-path", new BenchScene.Spawn(56.5, 70.0, -88.5, 204.0F, 6.0F), BenchScene.Weather.RAIN, 7_000, "water-reflection", "tree-line"),
		scene("heldout-badlands", BenchScene.SetKind.HELD_OUT, 20261103L, "mtlr-heldout-badlands", Biomes.BADLANDS, WorldPresets.AMPLIFIED, BenchScene.TerrainProfile.AMPLIFIED, "heldout-badlands-path", new BenchScene.Spawn(116.5, 132.0, 12.5, -76.0F, 20.0F), BenchScene.Weather.THUNDER, 15_000, "mesa-face", "deep-shadow"),
		scene("heldout-snowy-peaks", BenchScene.SetKind.HELD_OUT, 20261104L, "mtlr-heldout-snowy-peaks", Biomes.SNOWY_SLOPES, WorldPresets.NORMAL, BenchScene.TerrainProfile.NORMAL, "heldout-snowy-peaks-path", new BenchScene.Spawn(-104.5, 176.0, 68.5, 158.0F, 24.0F), BenchScene.Weather.CLEAR, 21_000, "peak-profile", "snow-shadow"),
		scene("holdout_forest", BenchScene.SetKind.HELD_OUT, 20261105L, "mtlr-holdout-forest", Biomes.FOREST, WorldPresets.NORMAL, BenchScene.TerrainProfile.NORMAL, "holdout-forest-path", new BenchScene.Spawn(32.5, 104.0, -20.5, 135.0F, 12.0F), BenchScene.Weather.CLEAR, 9_000, "canopy", "ground-cover")
	);

	private BenchScenes() {
	}

	public static List<BenchScene> development() {
		return DEVELOPMENT;
	}

	public static List<BenchScene> heldOut() {
		return HELD_OUT;
	}

	public static Optional<BenchScene> byId(String id) {
		Optional<BenchScene> exact = all().stream().filter(scene -> scene.id().equals(id)).findFirst();
		return exact.isPresent() ? exact : all().stream().filter(scene -> scene.id().equals(id.replace('_', '-'))).findFirst();
	}

	public static int manifestVersion() {
		return MANIFEST_VERSION;
	}

	private static List<BenchScene> all() {
		return java.util.stream.Stream.concat(DEVELOPMENT.stream(), HELD_OUT.stream()).toList();
	}

	private static BenchScene scene(String id, BenchScene.SetKind setKind, long seed, String worldName, ResourceKey<Biome> biome, ResourceKey<WorldPreset> preset, BenchScene.TerrainProfile profile, String pathId, BenchScene.Spawn spawn, BenchScene.Weather weather, int timeOfDay, String... imagePoints) {
		return new BenchScene(id, setKind, seed, worldName, new BenchScene.TerrainIntent(biome, preset, profile), path(pathId, spawn), spawn, weather, timeOfDay, List.of(imagePoints));
	}

	private static CameraPath path(String id, BenchScene.Spawn spawn) {
		return new CameraPath(id, CameraPath.Interpolation.SMOOTH, List.of(
			new CameraPath.Waypoint(0, 0.0, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()),
			new CameraPath.Waypoint(1, 4.0, spawn.x() + 24.0, spawn.y() + 5.0, spawn.z() + 16.0, spawn.yaw() + 35.0F, spawn.pitch()),
			new CameraPath.Waypoint(2, 8.0, spawn.x() - 16.0, spawn.y() + 2.0, spawn.z() + 32.0, spawn.yaw() - 40.0F, spawn.pitch() + 3.0F),
			new CameraPath.Waypoint(3, 12.0, spawn.x() - 32.0, spawn.y() + 8.0, spawn.z() - 12.0, spawn.yaw() - 85.0F, spawn.pitch() - 2.0F)
		));
	}
}
