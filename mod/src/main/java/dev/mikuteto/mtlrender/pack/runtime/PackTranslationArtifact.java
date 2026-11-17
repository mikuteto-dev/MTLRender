package dev.mikuteto.mtlrender.pack.runtime;

import java.util.List;
import java.util.Objects;

public record PackTranslationArtifact(
	String vertexMsl,
	String vertexEntry,
	String fragmentMsl,
	String fragmentEntry,
	Reflection reflection
) {
	public PackTranslationArtifact {
		required(vertexMsl, "vertex MSL");
		required(vertexEntry, "vertex entry");
		required(fragmentMsl, "fragment MSL");
		required(fragmentEntry, "fragment entry");
		Objects.requireNonNull(reflection, "reflection");
	}

	private static void required(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isEmpty()) throw new IllegalArgumentException(label + " must not be empty");
	}

	public record Reflection(
		List<ResourceBinding> bindings,
		List<VertexInput> vertexInputs,
		List<FragmentOutput> fragmentOutputs,
		DepthState depth
	) {
		public Reflection {
			bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
			vertexInputs = List.copyOf(Objects.requireNonNull(vertexInputs, "vertex inputs"));
			fragmentOutputs = List.copyOf(Objects.requireNonNull(fragmentOutputs, "fragment outputs"));
		}
	}

	public record ResourceBinding(String name, Kind kind, int slot, int stages) {
		public ResourceBinding {
			Objects.requireNonNull(name, "binding name");
			Objects.requireNonNull(kind, "binding kind");
			if (name.isEmpty() || slot < 0 || stages <= 0) throw new IllegalArgumentException("invalid resource binding");
		}
	}

	public record VertexInput(String name, int location, String format, int bufferSlot, int offset) {
		public VertexInput {
			Objects.requireNonNull(name, "vertex input name");
			Objects.requireNonNull(format, "vertex input format");
			if (name.isEmpty() || format.isEmpty() || location < 0 || bufferSlot < 0 || offset < 0) {
				throw new IllegalArgumentException("invalid vertex input");
			}
		}
	}

	public record FragmentOutput(String name, int location, int colorAttachment) {
		public FragmentOutput {
			Objects.requireNonNull(name, "fragment output name");
			if (name.isEmpty() || location < 0 || colorAttachment < 0) throw new IllegalArgumentException("invalid fragment output");
		}
	}

	public record DepthState(String format, String compare, boolean write) {
		public DepthState {
			Objects.requireNonNull(format, "depth format");
			Objects.requireNonNull(compare, "depth compare");
			if (format.isEmpty() || compare.isEmpty()) throw new IllegalArgumentException("invalid depth state");
		}
	}

	public enum Kind { TEXTURE, SAMPLER, BUFFER, IMAGE }
}
