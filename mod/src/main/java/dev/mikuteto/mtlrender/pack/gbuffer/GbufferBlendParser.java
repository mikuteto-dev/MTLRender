package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.Locale;
import java.util.Optional;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;

public final class GbufferBlendParser {
	private GbufferBlendParser() {
	}

	public static Optional<BlendFunction> parse(String value) {
		if (value == null || value.trim().equalsIgnoreCase("off")) {
			return Optional.empty();
		}
		String[] parts = value.trim().split("\\s+");
		if (parts.length != 4 && parts.length != 6) {
			return Optional.empty();
		}
		BlendFactor sourceColor = factor(parts[0]);
		BlendFactor destinationColor = factor(parts[1]);
		BlendFactor sourceAlpha = factor(parts[parts.length == 4 ? 2 : 3]);
		BlendFactor destinationAlpha = factor(parts[parts.length == 4 ? 3 : 4]);
		if (sourceColor == null || destinationColor == null || sourceAlpha == null || destinationAlpha == null) {
			return Optional.empty();
		}
		BlendOp colorOperation = parts.length == 6 ? operation(parts[2]) : BlendOp.ADD;
		BlendOp alphaOperation = parts.length == 6 ? operation(parts[5]) : BlendOp.ADD;
		if (colorOperation == null || alphaOperation == null) {
			return Optional.empty();
		}
		return Optional.of(new BlendFunction(sourceColor, destinationColor, colorOperation,
			sourceAlpha, destinationAlpha, alphaOperation));
	}

	public static boolean isOff(String value) {
		return value != null && (value.trim().equalsIgnoreCase("off") || value.trim().equalsIgnoreCase("false"));
	}

	private static BlendFactor factor(String value) {
		try {
			return BlendFactor.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static BlendOp operation(String value) {
		try {
			return BlendOp.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}
}
