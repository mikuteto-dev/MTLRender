package dev.mikuteto.mtlrender.pack.tess;

import java.util.Objects;
import java.util.Optional;

public enum TessellationStrategy {
	AUTO,
	COMPUTE,
	MESH;

	public boolean available(Capabilities capabilities) {
		Objects.requireNonNull(capabilities, "capabilities");
		return switch (this) {
			case AUTO -> capabilities.compute() || capabilities.mesh();
			case COMPUTE -> capabilities.compute();
			case MESH -> capabilities.mesh();
		};
	}

	public Optional<TessellationStrategy> resolve(Capabilities capabilities) {
		Objects.requireNonNull(capabilities, "capabilities");
		return switch (this) {
			case AUTO -> capabilities.compute() ? Optional.of(COMPUTE)
				: capabilities.mesh() ? Optional.of(MESH) : Optional.empty();
			case COMPUTE -> capabilities.compute() ? Optional.of(COMPUTE) : Optional.empty();
			case MESH -> capabilities.mesh() ? Optional.of(MESH) : Optional.empty();
		};
	}

	public static TessellationStrategy fromConfig(String configured, TessellationStrategy automatic) {
		Objects.requireNonNull(automatic, "automatic");
		if (configured == null || "auto".equalsIgnoreCase(configured)) {
			return automatic;
		}
		return switch (configured.toLowerCase(java.util.Locale.ROOT)) {
			case "compute" -> COMPUTE;
			case "mesh" -> MESH;
			default -> automatic;
		};
	}

	public static Capabilities currentCapabilities() {
		return new Capabilities(true, false);
	}

	public record Capabilities(boolean compute, boolean mesh) {
	}
}
