package dev.mikuteto.mtlrender.terrain;

import dev.mikuteto.mtlrender.pack.shadow.ShadowPassPlan;

import java.util.Arrays;
import java.util.Objects;

public record ShadowCullView(ShadowPassPlan plan, float[] clip, int cameraX, int cameraY, int cameraZ,
	float offsetX, float offsetY, float offsetZ) {
	public ShadowCullView {
		Objects.requireNonNull(plan, "shadow plan");
		Objects.requireNonNull(clip, "shadow clip");
		if (clip.length != 16) throw new IllegalArgumentException("shadow clip must contain 16 values");
		clip = Arrays.copyOf(clip, clip.length);
	}

	@Override
	public float[] clip() {
		return Arrays.copyOf(clip, clip.length);
	}
}
