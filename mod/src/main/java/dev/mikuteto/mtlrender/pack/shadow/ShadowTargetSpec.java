package dev.mikuteto.mtlrender.pack.shadow;

import com.mojang.blaze3d.GpuFormat;

import java.util.Objects;
import java.util.Optional;

public record ShadowTargetSpec(String name, Kind kind, boolean present, int resolution, String format,
	GpuFormat metalFormat, boolean mipmap, boolean nearest, boolean hardwareFiltering, boolean clear,
	Optional<ClearColor> clearColor) {
	public ShadowTargetSpec {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(format, "format");
		Objects.requireNonNull(metalFormat, "metalFormat");
		Objects.requireNonNull(clearColor, "clearColor");
		if (resolution <= 0) throw new IllegalArgumentException("resolution must be positive");
	}

	public enum Kind {
		DEPTH,
		COLOR
	}

	public record ClearColor(float red, float green, float blue, float alpha) {
	}
}
