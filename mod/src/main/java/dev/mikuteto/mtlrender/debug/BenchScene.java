package dev.mikuteto.mtlrender.debug;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

public record BenchScene(
	String id,
	SetKind setKind,
	long seed,
	String worldName,
	TerrainIntent terrain,
	CameraPath cameraPath,
	Spawn spawn,
	Weather weather,
	int timeOfDay,
	List<String> expectedImagePointIds
) {
	public BenchScene {
		if (id.isBlank() || worldName.isBlank()) {
			throw new IllegalArgumentException("scene id and world name must not be blank");
		}
		Objects.requireNonNull(setKind);
		Objects.requireNonNull(terrain);
		Objects.requireNonNull(cameraPath);
		Objects.requireNonNull(spawn);
		Objects.requireNonNull(weather);
		if (timeOfDay < 0 || timeOfDay >= 24_000) {
			throw new IllegalArgumentException("time of day must be in a Minecraft day");
		}
		expectedImagePointIds = List.copyOf(expectedImagePointIds);
	}

	public enum SetKind {
		DEVELOPMENT,
		HELD_OUT
	}

	public enum Weather {
		CLEAR,
		RAIN,
		THUNDER
	}

	public enum TerrainProfile {
		NORMAL,
		AMPLIFIED,
		LARGE_BIOMES
	}

	public record TerrainIntent(ResourceKey<Biome> biome, ResourceKey<WorldPreset> worldPreset, TerrainProfile profile) {
		public TerrainIntent {
			Objects.requireNonNull(biome);
			Objects.requireNonNull(worldPreset);
			Objects.requireNonNull(profile);
		}
	}

	public record Spawn(double x, double y, double z, float yaw, float pitch) {
		public Spawn {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
				throw new IllegalArgumentException("spawn must be finite");
			}
		}
	}
}
