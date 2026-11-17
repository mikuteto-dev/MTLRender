package dev.mikuteto.mtlrender.pack.precision;

import java.util.Objects;

public record BufferUsage(
	String id,
	String requestedFormat,
	String effectiveMetalFormat,
	ChannelMask reads,
	ChannelMask writes,
	NumericUsage numericUsage,
	ResourceType resourceType,
	boolean dynamicOutputSelection
) {
	public BufferUsage {
		Objects.requireNonNull(id);
		Objects.requireNonNull(requestedFormat);
		Objects.requireNonNull(effectiveMetalFormat);
		Objects.requireNonNull(reads);
		Objects.requireNonNull(writes);
		Objects.requireNonNull(numericUsage);
		Objects.requireNonNull(resourceType);
	}

	public enum ResourceType {
		COLOR_ATTACHMENT,
		DEPTH,
		INTEGER,
		IMAGE,
		SSBO,
		UNKNOWN
	}

	public boolean channelUsageKnown() {
		return !reads.unknown() && !writes.unknown();
	}

	public boolean reductionSafe() {
		return resourceType == ResourceType.COLOR_ATTACHMENT
			&& channelUsageKnown()
			&& !dynamicOutputSelection
			&& numericUsage.safeForReduction();
	}
}
