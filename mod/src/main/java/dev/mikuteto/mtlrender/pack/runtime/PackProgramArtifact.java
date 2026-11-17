package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PackProgramArtifact(PackProgramRequest request, RuntimeProgram runtimeProgram,
		Map<Program.Stage, String> translatedSources, Optional<MeshMetadata> mesh,
		Optional<PackPipelineDesc.LayerInstancing> layerInstancing,
		Optional<GeometryPathSelector.Result> geometrySelection) {
	public PackProgramArtifact(PackProgramRequest request, RuntimeProgram runtimeProgram,
		Map<Program.Stage, String> translatedSources) {
		this(request, runtimeProgram, translatedSources, Optional.empty(), Optional.empty(), Optional.empty());
	}

	public PackProgramArtifact(PackProgramRequest request, RuntimeProgram runtimeProgram,
		Map<Program.Stage, String> translatedSources, Optional<MeshMetadata> mesh) {
		this(request, runtimeProgram, translatedSources, mesh, Optional.empty(), Optional.empty());
	}

	public PackProgramArtifact(PackProgramRequest request, RuntimeProgram runtimeProgram,
		Map<Program.Stage, String> translatedSources, Optional<MeshMetadata> mesh,
		Optional<PackPipelineDesc.LayerInstancing> layerInstancing) {
		this(request, runtimeProgram, translatedSources, mesh, layerInstancing, Optional.empty());
	}

	public PackProgramArtifact {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(runtimeProgram, "runtime program");
		Objects.requireNonNull(translatedSources, "translated sources");
		Objects.requireNonNull(mesh, "mesh");
		Objects.requireNonNull(layerInstancing);
		Objects.requireNonNull(geometrySelection);
		if (!translatedSources.containsKey(Program.Stage.VERTEX)
			|| !translatedSources.containsKey(Program.Stage.FRAGMENT)) {
			throw new IllegalArgumentException("translated render program requires vertex and fragment sources");
		}
		if (translatedSources.containsKey(Program.Stage.COMPUTE)) {
			throw new IllegalArgumentException("compute source is not part of a render artifact");
		}
		EnumMap<Program.Stage, String> copy = new EnumMap<>(Program.Stage.class);
		for (Map.Entry<Program.Stage, String> entry : translatedSources.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "translated stage");
			copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "translated source"));
		}
		translatedSources = Map.copyOf(copy);
		if (mesh.isPresent() && (!request.source(Program.Stage.GEOMETRY).isPresent()
			|| !translatedSources.containsKey(Program.Stage.GEOMETRY))) {
			throw new IllegalArgumentException("mesh metadata requires a translated geometry stage");
		}
		if (layerInstancing.isPresent() && (!request.source(Program.Stage.GEOMETRY).isPresent()
			|| translatedSources.containsKey(Program.Stage.GEOMETRY) || mesh.isPresent())) {
			throw new IllegalArgumentException("layer instancing requires a lowered geometry stage");
		}
	}

	public RuntimeProgram runtime() {
		return runtimeProgram;
	}

	public record MeshMetadata(String objectMsl, String meshMsl, String objectEntry, String meshEntry,
		String fragmentEntry, GeometryPlan.PrimitiveTopology topology, int payloadBytes, int vertexCount,
		int primitiveCount, int vertexStride, int primitiveStride, List<Reflection.Resource> resources,
		List<Reflection.FragmentOutput> outputs, List<PackPipelineDesc.Binding> bindings,
		MetalAccess.MeshDispatch dispatch) {
		public MeshMetadata {
			required(objectMsl, "object MSL");
			required(meshMsl, "mesh MSL");
			required(objectEntry, "object entry");
			required(meshEntry, "mesh entry");
			required(fragmentEntry, "fragment entry");
			Objects.requireNonNull(topology, "topology");
			if (payloadBytes <= 0 || payloadBytes > 16384 || vertexCount <= 0 || vertexCount > 256
				|| primitiveCount <= 0 || primitiveCount > 512 || vertexStride <= 0 || primitiveStride <= 0) {
				throw new IllegalArgumentException("invalid mesh output budget");
			}
			resources = copy(resources, "resources");
			outputs = copy(outputs, "outputs");
			bindings = copy(bindings, "bindings");
			Objects.requireNonNull(dispatch, "dispatch");
		}

		private static <T> List<T> copy(List<T> values, String label) {
			Objects.requireNonNull(values, label);
			for (T value : values) Objects.requireNonNull(value, label + " entry");
			return List.copyOf(values);
		}

		private static void required(String value, String label) {
			Objects.requireNonNull(value, label);
			if (value.isEmpty()) throw new IllegalArgumentException(label + " must not be empty");
		}
	}
}
