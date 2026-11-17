package dev.mikuteto.mtlrender.pack.tess;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TessellationDescriptor implements Serializable {
	@Serial
	private static final long serialVersionUID = 1L;

	public enum Topology {
		TRIANGLES(3, 1),
		QUADS(4, 2),
		ISOLINES(2, 0);

		private final int outerFactorCount;
		private final int innerFactorCount;

		Topology(int outerFactorCount, int innerFactorCount) {
			this.outerFactorCount = outerFactorCount;
			this.innerFactorCount = innerFactorCount;
		}
	}

	public enum Spacing {
		EQUAL,
		FRACTIONAL_EVEN,
		FRACTIONAL_ODD
	}

	private final Topology topology;
	private final Spacing spacing;
	private final int patchVertexCount;
	private final int outputVertexCount;
	private final List<Integer> outerFactorLocations;
	private final List<Integer> innerFactorLocations;
	private final String sourceProgramIdentity;

	public TessellationDescriptor(Topology topology, Spacing spacing, int patchVertexCount,
		int outputVertexCount, List<Integer> outerFactorLocations, List<Integer> innerFactorLocations,
		String sourceProgramIdentity) {
		this.topology = Objects.requireNonNull(topology, "topology");
		this.spacing = Objects.requireNonNull(spacing, "spacing");
		if (patchVertexCount <= 0) {
			throw new IllegalArgumentException("patch vertex count must be positive");
		}
		if (outputVertexCount <= 0) {
			throw new IllegalArgumentException("output vertex count must be positive");
		}
		this.patchVertexCount = patchVertexCount;
		this.outputVertexCount = outputVertexCount;
		this.outerFactorLocations = locations(outerFactorLocations, topology.outerFactorCount, "outer");
		this.innerFactorLocations = locations(innerFactorLocations, topology.innerFactorCount, "inner");
		this.sourceProgramIdentity = requireIdentity(sourceProgramIdentity);
	}

	public TessellationDescriptor(Topology topology, Spacing spacing, int patchVertexCount,
		int outputVertexCount, int[] outerFactorLocations, int[] innerFactorLocations,
		String sourceProgramIdentity) {
		this(topology, spacing, patchVertexCount, outputVertexCount,
			toList(outerFactorLocations, "outer factor locations"),
			toList(innerFactorLocations, "inner factor locations"), sourceProgramIdentity);
	}

	public Topology topology() {
		return topology;
	}

	public Spacing spacing() {
		return spacing;
	}

	public int patchVertexCount() {
		return patchVertexCount;
	}

	public int outputVertexCount() {
		return outputVertexCount;
	}

	public List<Integer> outerFactorLocations() {
		return outerFactorLocations;
	}

	public List<Integer> innerFactorLocations() {
		return innerFactorLocations;
	}

	public String sourceProgramIdentity() {
		return sourceProgramIdentity;
	}

	private static List<Integer> locations(List<Integer> values, int expected, String label) {
		Objects.requireNonNull(values, label + " factor locations");
		if (values.size() != expected) {
			throw new IllegalArgumentException(label + " factor location count must be " + expected);
		}
		List<Integer> copy = new ArrayList<>(values.size());
		for (Integer value : values) {
			if (value == null || value < 0) {
				throw new IllegalArgumentException(label + " factor locations must be non-negative");
			}
			if (copy.contains(value)) {
				throw new IllegalArgumentException(label + " factor locations must be unique");
			}
			copy.add(value);
		}
		return List.copyOf(copy);
	}

	private static List<Integer> toList(int[] values, String label) {
		Objects.requireNonNull(values, label);
		List<Integer> result = new ArrayList<>(values.length);
		for (int value : values) {
			result.add(value);
		}
		return result;
	}

	private static String requireIdentity(String value) {
		Objects.requireNonNull(value, "source program identity");
		if (value.isBlank()) {
			throw new IllegalArgumentException("source program identity must not be blank");
		}
		return value;
	}
}
