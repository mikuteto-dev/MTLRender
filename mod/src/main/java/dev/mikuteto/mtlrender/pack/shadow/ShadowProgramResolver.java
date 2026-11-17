package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramId;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ShadowProgramResolver {
	private final ProgramSet programs;
	private final ShaderProperties properties;
	private final Set<String> disabledPrograms;

	public ShadowProgramResolver(ProgramSet programs, ShaderProperties properties) {
		this(programs, properties, Set.of());
	}

	public ShadowProgramResolver(ProgramSet programs, ShaderProperties properties, Set<String> disabledPrograms) {
		this.programs = Objects.requireNonNull(programs, "programs");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.disabledPrograms = Set.copyOf(disabledPrograms);
	}

	public ShadowProgramSelection resolve(ShadowProgramKind kind) {
		return resolve(kind, false);
	}

	public ShadowProgramSelection resolve(ShadowProgramKind kind, boolean optional) {
		Objects.requireNonNull(kind, "kind");
		ProgramId requested = kind.program();
		var resolved = programs.resolve(requested);
		var enabledResolved = resolveEnabled(requested);
		if (resolved.isEmpty() || enabledResolved.isEmpty()
			|| !resolved.get().name().equals(enabledResolved.get().name())) {
			resolved = enabledResolved;
		}
		String resolvedName = resolved.map(Program::name).orElse(null);
		List<ProgramId> path = path(requested, resolvedName);
		if (invalidRenderSource(path, resolvedName)) {
			resolved = java.util.Optional.empty();
		}
		if (resolved.isPresent()) {
			Program program = resolved.get();
			return new ShadowProgramSelection(kind, resolved, program.name(), path, optional, false);
		}
		boolean disabled = disabled(requested);
		return new ShadowProgramSelection(kind, java.util.Optional.empty(), requested.sourceName(), path,
			optional, disabled);
	}

	private java.util.Optional<Program> resolveEnabled(ProgramId requested) {
		ProgramId current = requested;
		while (current != null) {
			if (isDisabled(current)) {
				current = current.fallback().orElse(null);
				continue;
			}
			Program program = programs.program(current.sourceName()).orElse(null);
			if (program != null) {
				if (program.isRenderProgram()) {
					return java.util.Optional.of(program);
				}
				if (hasRenderStage(program)) {
					return java.util.Optional.empty();
				}
			}
			current = current.fallback().orElse(null);
		}
		return java.util.Optional.empty();
	}

	private List<ProgramId> path(ProgramId requested, String resolvedName) {
		List<ProgramId> result = new ArrayList<>();
		ProgramId current = requested;
		while (current != null) {
			result.add(current);
			if (current.sourceName().equals(resolvedName)) {
				break;
			}
			Program program = programs.program(current.sourceName()).orElse(null);
			if (program != null && !isDisabled(current) && hasRenderStage(program) && !program.isRenderProgram()) {
				break;
			}
			current = current.fallback().orElse(null);
		}
		return result;
	}

	private boolean invalidRenderSource(List<ProgramId> path, String resolvedName) {
		ProgramId last = path.getLast();
		if (last.sourceName().equals(resolvedName)) {
			return false;
		}
		Program program = programs.program(last.sourceName()).orElse(null);
		return program != null && hasRenderStage(program) && !program.isRenderProgram();
	}

	private static boolean hasRenderStage(Program program) {
		return program.source(Program.Stage.VERTEX).isPresent()
			|| program.source(Program.Stage.GEOMETRY).isPresent()
			|| program.source(Program.Stage.TESS_CONTROL).isPresent()
			|| program.source(Program.Stage.TESS_EVALUATION).isPresent()
			|| program.source(Program.Stage.FRAGMENT).isPresent();
	}

	private boolean disabled(ProgramId requested) {
		ProgramId current = requested;
		while (current != null) {
			if (isDisabled(current)) {
				return true;
			}
			current = current.fallback().orElse(null);
		}
		return false;
	}

	private boolean isDisabled(ProgramId id) {
		String name = id.sourceName();
		String dimensionName = programs.dimension().isEmpty() ? name : programs.dimension() + "/" + name;
		return disabledPrograms.contains(name) || disabledPrograms.contains(dimensionName)
			|| disabledCondition(name) || disabledCondition(dimensionName);
	}

	private boolean disabledCondition(String name) {
		Map<String, String> enabled = properties.programEnabled();
		String condition = enabled.get(name);
		if (condition == null) {
			return false;
		}
		String value = condition.trim();
		return value.equalsIgnoreCase("false") || value.equals("0");
	}
}
