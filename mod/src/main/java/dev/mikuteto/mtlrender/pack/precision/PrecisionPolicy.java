package dev.mikuteto.mtlrender.pack.precision;

import java.util.List;

import dev.mikuteto.mtlrender.config.Config;

public record PrecisionPolicy(boolean reduce32To16, boolean reduceRgb11) {
	public static final String REDUCE_32_TO_16_PROPERTY = "mtlrender.packPrecision32To16";
	public static final String REDUCE_RGB11_PROPERTY = "mtlrender.packPrecisionRgb11";

	public static PrecisionPolicy defaults() {
		return new PrecisionPolicy(false, false);
	}

	public static PrecisionPolicy fromSystemProperties() {
		return new PrecisionPolicy(Boolean.getBoolean(REDUCE_32_TO_16_PROPERTY),
			Boolean.getBoolean(REDUCE_RGB11_PROPERTY));
	}

	public static PrecisionPolicy fromConfig(Config.ShaderPackPrecision config) {
		return new PrecisionPolicy(config.reduce32To16, config.reduceRgb11);
	}

	public PrecisionDecision decide(BufferUsage usage) {
		if (usage.requestedFormat().equals("RGBA32F") && usage.effectiveMetalFormat().equals("RGBA32_FLOAT")) {
			if (!usage.reductionSafe()) return ineligible(usage, "float precision evidence is incomplete or unsafe");
			if (!reduce32To16) return eligible(usage, "eligible but disabled: 32-bit to 16-bit policy is off");
			return changed(usage, ReductionKind.RGBA32F_TO_RGBA16F, "proven-safe float precision reduction");
		}
		if (usage.requestedFormat().equals("RGBA16F") && usage.effectiveMetalFormat().equals("RGBA16_FLOAT")) {
			if (!usage.reductionSafe()) return ineligible(usage, "float RGB precision evidence is incomplete or unsafe");
			if (!usage.reads().alphaUnused() || !usage.writes().alphaUnused()) {
				return ineligible(usage, "alpha is read or written");
			}
			if (!reduceRgb11) return eligible(usage, "eligible but disabled: RGB11 policy is off");
			return changed(usage, ReductionKind.RGBA16F_TO_RG11B10F, "alpha is provably unused and RGB is non-negative");
		}
		return ineligible(usage, "format is not a supported precision reduction source");
	}

	public PrecisionReport report(List<BufferUsage> usages) {
		return new PrecisionReport(usages.stream().map(this::decide).toList());
	}

	private static PrecisionDecision changed(BufferUsage usage, ReductionKind kind, String reason) {
		String format = kind == ReductionKind.RGBA32F_TO_RGBA16F ? "RGBA16_FLOAT" : "RG11B10_FLOAT";
		return new PrecisionDecision(usage.id(), usage.requestedFormat(), format, kind, true, reason);
	}

	private static PrecisionDecision eligible(BufferUsage usage, String reason) {
		return new PrecisionDecision(usage.id(), usage.requestedFormat(), usage.effectiveMetalFormat(),
			ReductionKind.NONE, true, reason);
	}

	private static PrecisionDecision ineligible(BufferUsage usage, String reason) {
		return new PrecisionDecision(usage.id(), usage.requestedFormat(), usage.effectiveMetalFormat(),
			ReductionKind.NONE, false, reason);
	}
}
