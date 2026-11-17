package dev.mikuteto.mtlrender.pack.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GeometryPathSelector {
	private GeometryPathSelector() {
	}

	public enum ProgramKind { RENDER, COMPUTE }
	public enum GsPattern { NONE, PASS_THROUGH, LAYER_INSTANCE, GENERAL }
	public enum Path { PASS_THROUGH, LAYER_INSTANCED, MESH, COMPUTE }
	public enum RejectionReason {
		COMPUTE_PROGRAM,
		NOT_ANALYZED,
		UNSUPPORTED_PATTERN,
		UNSUPPORTED_TOPOLOGY,
		OUTPUT_LIMIT,
		UNREPRESENTABLE_SIDE_EFFECTS,
		LOWERING_FAILED,
		MISSING_COMPUTE_PLAN
	}

	public record ProgramIdentity(ProgramKind kind, String name) {
		public ProgramIdentity {
			kind = Objects.requireNonNull(kind, "program kind");
			name = GeometryPathSelector.name(name, "program name");
		}
	}

	public record AnalyzerResult(GsPattern pattern, boolean analyzed) {
		public AnalyzerResult {
			pattern = Objects.requireNonNull(pattern, "GS pattern");
		}

		public static AnalyzerResult passThrough() {
			return new AnalyzerResult(GsPattern.PASS_THROUGH, true);
		}

		public static AnalyzerResult layerInstance() {
			return new AnalyzerResult(GsPattern.LAYER_INSTANCE, true);
		}

		public static AnalyzerResult general() {
			return new AnalyzerResult(GsPattern.GENERAL, true);
		}
	}

	public record MeshContract(Set<GeometryPlan.PrimitiveTopology> topologies,
		Set<GeometryPlan.SideEffect> sideEffects) {
		public MeshContract {
			topologies = Set.copyOf(Objects.requireNonNull(topologies, "mesh topologies"));
			sideEffects = Set.copyOf(Objects.requireNonNull(sideEffects, "mesh side effects"));
		}

		public boolean supports(GeometryPlan plan) {
			return plan.outputBudget().vertices() <= GeometryPlan.MAX_MESH_VERTICES
				&& plan.outputBudget().primitives() <= GeometryPlan.MAX_MESH_PRIMITIVES
				&& topologies.contains(plan.topology())
				&& sideEffects.containsAll(plan.sideEffects());
		}
	}

	public interface Lowering {
		Attempt lower(Path path, Input input);
	}

	public record Attempt(boolean accepted, RejectionReason reason, String detail) {
		public Attempt {
			if (!accepted) {
				reason = Objects.requireNonNull(reason, "rejection reason");
				detail = name(detail, "rejection detail");
			} else if (reason != null || detail != null) {
				throw new IllegalArgumentException("accepted attempt cannot have rejection context");
			}
		}

		public static Attempt success() {
			return new Attempt(true, null, null);
		}

		public static Attempt rejected(RejectionReason reason, String detail) {
			return new Attempt(false, reason, detail);
		}
	}

	public record Input(String pack, String dimension, ProgramIdentity program, GeometryPlan geometry,
		AnalyzerResult analyzer, MeshContract mesh, Optional<GeometryComputePlan> computePlan) {
		public Input {
			pack = name(pack, "pack");
			dimension = Objects.requireNonNull(dimension, "dimension");
			program = Objects.requireNonNull(program, "program");
			geometry = Objects.requireNonNull(geometry, "geometry plan");
			analyzer = Objects.requireNonNull(analyzer, "analyzer result");
			mesh = Objects.requireNonNull(mesh, "mesh contract");
			computePlan = Objects.requireNonNull(computePlan, "compute plan");
		}
	}

	public record Diagnostic(Path path, RejectionReason reason, String detail) {
		public Diagnostic {
			path = Objects.requireNonNull(path, "path");
			reason = Objects.requireNonNull(reason, "rejection reason");
			detail = name(detail, "diagnostic detail");
		}
	}

	public record Rejection(List<Diagnostic> diagnostics) {
		public Rejection {
			diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
			if (diagnostics.isEmpty()) throw new IllegalArgumentException("terminal rejection needs diagnostics");
		}
	}

	public record Result(String pack, String dimension, ProgramIdentity program, GsPattern pattern,
		Path selected, Rejection terminalRejection, GeometryPlan.OutputBudget outputBudget,
		Set<GeometryPlan.SideEffect> sideEffects, List<Path> attempts, List<Path> remainingAttempts,
		List<Diagnostic> diagnostics) {
		public Result {
			pack = name(pack, "pack");
			dimension = Objects.requireNonNull(dimension, "dimension");
			program = Objects.requireNonNull(program, "program");
			pattern = Objects.requireNonNull(pattern, "GS pattern");
			if ((selected == null) == (terminalRejection == null)) throw new IllegalArgumentException("result must select or reject");
			outputBudget = Objects.requireNonNull(outputBudget, "output budget");
			sideEffects = Set.copyOf(Objects.requireNonNull(sideEffects, "side effects"));
			attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
			remainingAttempts = List.copyOf(Objects.requireNonNull(remainingAttempts, "remaining attempts"));
			diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
		}

		public Optional<Path> selectedPath() {
			return Optional.ofNullable(selected);
		}
	}

	public static Result select(Input input, Lowering lowering) {
		Objects.requireNonNull(input, "input");
		Objects.requireNonNull(lowering, "lowering");
		GeometryPlan geometry = input.geometry();
		List<Path> candidates = candidates(input);
		List<Diagnostic> diagnostics = new java.util.ArrayList<>();
		if (input.program().kind() == ProgramKind.COMPUTE) {
			diagnostics.add(new Diagnostic(Path.COMPUTE, RejectionReason.COMPUTE_PROGRAM, "compute programs have no geometry path"));
			return new Result(input.pack(), input.dimension(), input.program(), input.analyzer().pattern(), null,
				new Rejection(diagnostics), input.geometry().outputBudget(), input.geometry().sideEffects(), candidates, List.of(), diagnostics);
		}
		for (int index = 0; index < candidates.size(); index++) {
			Path path = candidates.get(index);
			Attempt attempt = admissibility(path, input);
			if (attempt == null) attempt = Objects.requireNonNull(lowering.lower(path, input), "lowering result");
			if (attempt.accepted()) {
				return new Result(input.pack(), input.dimension(), input.program(), input.analyzer().pattern(), path, null,
					geometry.outputBudget(), geometry.sideEffects(), candidates, candidates.subList(index + 1, candidates.size()), diagnostics);
			}
			diagnostics.add(new Diagnostic(path, attempt.reason(), attempt.detail()));
		}
		return new Result(input.pack(), input.dimension(), input.program(), input.analyzer().pattern(), null,
			new Rejection(diagnostics), geometry.outputBudget(), geometry.sideEffects(), candidates, List.of(), diagnostics);
	}

	private static List<Path> candidates(Input input) {
		if (input.program().kind() == ProgramKind.COMPUTE) return List.of();
		if (input.geometry().presence() == GeometryPlan.GeometryPresence.ABSENT) return List.of(Path.PASS_THROUGH);
		java.util.ArrayList<Path> paths = new java.util.ArrayList<>();
		if (input.analyzer().pattern() == GsPattern.PASS_THROUGH) paths.add(Path.PASS_THROUGH);
		if (input.analyzer().pattern() == GsPattern.LAYER_INSTANCE) paths.add(Path.LAYER_INSTANCED);
		paths.add(Path.MESH);
		paths.add(Path.COMPUTE);
		return List.copyOf(paths);
	}

	private static Attempt admissibility(Path path, Input input) {
		if (input.program().kind() == ProgramKind.COMPUTE) return Attempt.rejected(RejectionReason.COMPUTE_PROGRAM, "compute programs have no geometry path");
		if (!input.analyzer().analyzed()) return Attempt.rejected(RejectionReason.NOT_ANALYZED, "geometry analyzer did not produce a result");
		if (path == Path.MESH && !input.mesh().supports(input.geometry())) {
			if (!input.mesh().topologies().contains(input.geometry().topology())) return Attempt.rejected(RejectionReason.UNSUPPORTED_TOPOLOGY, "mesh topology is unsupported");
			if (input.geometry().outputBudget().vertices() > GeometryPlan.MAX_MESH_VERTICES || input.geometry().outputBudget().primitives() > GeometryPlan.MAX_MESH_PRIMITIVES) return Attempt.rejected(RejectionReason.OUTPUT_LIMIT, "mesh output exceeds 256 vertices or 512 primitives");
			return Attempt.rejected(RejectionReason.UNREPRESENTABLE_SIDE_EFFECTS, "mesh side effects are not representable");
		}
		if (path == Path.COMPUTE && input.computePlan().isEmpty()) return Attempt.rejected(RejectionReason.MISSING_COMPUTE_PLAN, "compute emulation plan is unavailable");
		return null;
	}

	private static String name(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
		return value;
	}
}
