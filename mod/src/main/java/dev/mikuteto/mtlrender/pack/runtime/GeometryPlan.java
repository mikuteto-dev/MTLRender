package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record GeometryPlan(
	GeometryPresence presence,
	PrimitiveTopology topology,
	OutputBudget outputBudget,
	Set<SideEffect> sideEffects,
	LayerMetadata layers,
	GeometryPath path,
	StageIdentities stages,
	EntryPoints entryPoints
) {
	public static final int MAX_MESH_VERTICES = 256;
	public static final int MAX_MESH_PRIMITIVES = 512;

	public GeometryPlan {
		presence = Objects.requireNonNull(presence, "presence");
		topology = Objects.requireNonNull(topology, "topology");
		outputBudget = Objects.requireNonNull(outputBudget, "output budget");
		sideEffects = effects(sideEffects);
		layers = Objects.requireNonNull(layers, "layers");
		path = Objects.requireNonNull(path, "path");
		stages = Objects.requireNonNull(stages, "stages");
		entryPoints = Objects.requireNonNull(entryPoints, "entry points");
		validate(presence, outputBudget, layers, path, stages);
	}

	public static GeometryPlan select(PackProgramArtifact artifact, GeometryMetadata metadata) {
		Objects.requireNonNull(artifact, "artifact");
		Objects.requireNonNull(metadata, "metadata");
		Map<Program.Stage, String> sources = artifact.request().sourceMap();
		boolean sourceGeometry = sources.containsKey(Program.Stage.GEOMETRY);
		boolean translatedGeometry = artifact.translatedSources().containsKey(Program.Stage.GEOMETRY);
		if (sourceGeometry != translatedGeometry) {
			throw new IllegalArgumentException("geometry source and translated stage must agree");
		}
		if (metadata.presence() == GeometryPresence.PRESENT && !sourceGeometry) {
			throw new IllegalArgumentException("geometry metadata has no geometry source");
		}
		if (metadata.presence() == GeometryPresence.ABSENT && sourceGeometry) {
			throw new IllegalArgumentException("geometry source is missing geometry metadata");
		}
		if (!artifact.runtime().dimension().equals(artifact.request().dimension())) {
			throw new IllegalArgumentException("artifact dimension identity changed");
		}
		String identity = artifact.request().pack() + "\u0000" + artifact.request().dimension() + "\u0000" + artifact.request().program();
		GeometryPath path = choose(metadata);
		return new GeometryPlan(metadata.presence(), metadata.topology(), metadata.outputBudget(),
			metadata.sideEffects(), metadata.layers(), path, metadata.stages(), EntryPoints.generate(identity));
	}

	public Optional<String> geometryEntryPoint() {
		return presence == GeometryPresence.PRESENT ? Optional.of(entryPoints.geometry()) : Optional.empty();
	}

	private static GeometryPath choose(GeometryMetadata metadata) {
		if (metadata.presence() == GeometryPresence.ABSENT) {
			return GeometryPath.PASS_THROUGH;
		}
		if (metadata.layers().semantics() == LayerSemantics.INSTANCE) {
			return GeometryPath.LAYER_INSTANCED;
		}
		if (metadata.outputBudget().vertices() > MAX_MESH_VERTICES
			|| metadata.outputBudget().primitives() > MAX_MESH_PRIMITIVES) {
			return GeometryPath.COMPUTE;
		}
		return GeometryPath.MESH;
	}

	private static void validate(GeometryPresence presence, OutputBudget budget, LayerMetadata layers,
		GeometryPath path, StageIdentities stages) {
		if (presence == GeometryPresence.ABSENT && path != GeometryPath.PASS_THROUGH) {
			throw new IllegalArgumentException("absent geometry must pass through");
		}
		if (presence == GeometryPresence.PRESENT && path == GeometryPath.PASS_THROUGH) {
			throw new IllegalArgumentException("present geometry cannot pass through");
		}
		if (path == GeometryPath.MESH || path == GeometryPath.LAYER_INSTANCED) {
			if (budget.vertices() > MAX_MESH_VERTICES || budget.primitives() > MAX_MESH_PRIMITIVES) {
				throw new IllegalArgumentException("mesh output exceeds Metal geometry limits");
			}
		}
		if (path == GeometryPath.LAYER_INSTANCED && layers.semantics() != LayerSemantics.INSTANCE) {
			throw new IllegalArgumentException("layer-instanced path requires instance layer semantics");
		}
		if (path != GeometryPath.LAYER_INSTANCED && layers.semantics() == LayerSemantics.INSTANCE) {
			throw new IllegalArgumentException("instance layer semantics require layer-instanced path");
		}
		if (presence == GeometryPresence.PRESENT && stages.geometry().isEmpty()) {
			throw new IllegalArgumentException("present geometry requires a geometry stage identity");
		}
	}

	public enum GeometryPresence { ABSENT, PRESENT }
	public enum PrimitiveTopology { POINTS, LINES, TRIANGLES }
	public enum GeometryPath { PASS_THROUGH, LAYER_INSTANCED, MESH, COMPUTE }
	public enum SideEffect { IMAGE, SSBO, ATOMIC }
	public enum LayerSemantics { NONE, INSTANCE, EXPLICIT }

	public record OutputBudget(int vertices, int primitives) {
		public OutputBudget {
			if (vertices <= 0 || primitives <= 0) {
				throw new IllegalArgumentException("geometry output budget must be positive");
			}
		}
	}

	public record LayerMetadata(LayerSemantics semantics, int layers) {
		public LayerMetadata {
			Objects.requireNonNull(semantics, "layer semantics");
			if (layers < 0 || (semantics == LayerSemantics.NONE && layers != 0)
				|| (semantics != LayerSemantics.NONE && layers == 0)) {
				throw new IllegalArgumentException("inconsistent layer metadata");
			}
		}
	}

	public record StageIdentities(Map<Program.Stage, String> values) {
		public StageIdentities {
			Objects.requireNonNull(values, "stage identities");
			EnumMap<Program.Stage, String> copy = new EnumMap<>(Program.Stage.class);
			for (Map.Entry<Program.Stage, String> entry : values.entrySet()) {
				Program.Stage stage = Objects.requireNonNull(entry.getKey(), "stage identity");
				String identity = Objects.requireNonNull(entry.getValue(), "stage identity name");
				if (identity.isBlank()) throw new IllegalArgumentException("stage identity name must not be blank");
				if (stage == Program.Stage.COMPUTE) throw new IllegalArgumentException("compute stage is not geometry metadata");
				copy.put(stage, identity);
			}
			if (!copy.containsKey(Program.Stage.VERTEX) || !copy.containsKey(Program.Stage.FRAGMENT)) {
				throw new IllegalArgumentException("vertex and fragment stage identities are required");
			}
			values = Map.copyOf(copy);
		}

		public Optional<String> geometry() {
			return Optional.ofNullable(values.get(Program.Stage.GEOMETRY));
		}
	}

	public record EntryPoints(String vertex, String fragment, String geometry, String mesh, String compute) {
		public EntryPoints {
			vertex = name(vertex, "vertex");
			fragment = name(fragment, "fragment");
			geometry = name(geometry, "geometry");
			mesh = name(mesh, "mesh");
			compute = name(compute, "compute");
			if (Set.of(vertex, fragment, geometry, mesh, compute).size() != 5) {
				throw new IllegalArgumentException("generated entry points must be unique");
			}
		}

		static EntryPoints generate(String identity) {
			String suffix = digest(identity);
			return new EntryPoints("mtlr_vertex_" + suffix, "mtlr_fragment_" + suffix,
				"mtlr_geometry_" + suffix, "mtlr_mesh_" + suffix, "mtlr_compute_" + suffix);
		}

		private static String name(String value, String label) {
			Objects.requireNonNull(value, label);
			if (value.isBlank()) throw new IllegalArgumentException(label + " entry point must not be blank");
			return value;
		}
	}

	public record GeometryMetadata(GeometryPresence presence, PrimitiveTopology topology, OutputBudget outputBudget,
		Set<SideEffect> sideEffects, LayerMetadata layers, StageIdentities stages) {
		public GeometryMetadata {
			presence = Objects.requireNonNull(presence, "presence");
			topology = Objects.requireNonNull(topology, "topology");
			outputBudget = Objects.requireNonNull(outputBudget, "output budget");
			sideEffects = effects(sideEffects);
			layers = Objects.requireNonNull(layers, "layers");
			stages = Objects.requireNonNull(stages, "stages");
		}
	}

	private static Set<SideEffect> effects(Set<SideEffect> values) {
		Objects.requireNonNull(values, "side effects");
		if (values.isEmpty()) return Set.of();
		return Set.copyOf(EnumSet.copyOf(values));
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
