package dev.mikuteto.mtlrender.pack;

import dev.mikuteto.mtlrender.pack.dh.DhProgramKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ProgramSet {
	private static final List<String> ARRAY_GROUPS = List.of(
		"setup", "begin", "shadowcomp", "prepare", "deferred", "composite"
	);
	private static final Program.Stage[] RENDER_STAGES = {
		Program.Stage.VERTEX,
		Program.Stage.GEOMETRY,
		Program.Stage.TESS_CONTROL,
		Program.Stage.TESS_EVALUATION,
		Program.Stage.FRAGMENT
	};

	private final String dimension;
	private final Map<String, Program> programs;

	public ProgramSet(PackSource source, Includes prepared, ShaderProperties properties,
					  Map<String, String> optionMacros, String dimension, Set<String> disabledPrograms) {
		this.dimension = dimension;
		Map<String, Program> found = new LinkedHashMap<>();

		Set<String> renderNames = new LinkedHashSet<>();
		for (ProgramId id : ProgramId.values()) {
			renderNames.add(id.sourceName());
		}
		for (String group : ARRAY_GROUPS) {
			for (int index = 0; index < 100; index++) {
				String base = index == 0 ? group : group + index;
				renderNames.add(base);
				for (char letter = 'a'; letter <= 'z'; letter++) {
					renderNames.add(base + "_" + letter);
				}
			}
		}

		Set<String> computeNames = new LinkedHashSet<>();
		computeNames.add("shadow");
		computeNames.add("final");
		for (String group : ARRAY_GROUPS) {
			for (int index = 0; index < 100; index++) {
				String base = index == 0 ? group : group + index;
				computeNames.add(base);
				for (char letter = 'a'; letter <= 'z'; letter++) {
					computeNames.add(base + "_" + letter);
				}
			}
		}

		for (String name : renderNames) {
			if (!enabled(name, properties, optionMacros, disabledPrograms)) {
				continue;
			}
			Map<Program.Stage, String> stages = new LinkedHashMap<>();
			for (Program.Stage stage : RENDER_STAGES) {
				read(prepared, name, stage).ifPresent(text -> stages.put(stage, text));
			}
			if (!stages.isEmpty()) {
				found.put(name, new Program(name, stages));
			}
		}

		for (String name : computeNames) {
			if (!enabled(name, properties, optionMacros, disabledPrograms)) {
				continue;
			}
			read(prepared, name, Program.Stage.COMPUTE)
				.ifPresent(text -> merge(found, name, Map.of(Program.Stage.COMPUTE, text)));
		}

		this.programs = Collections.unmodifiableMap(new LinkedHashMap<>(found));
	}

	private static void merge(Map<String, Program> programs, String name, Map<Program.Stage, String> sources) {
		Program existing = programs.get(name);
		if (existing == null) {
			programs.put(name, new Program(name, sources));
			return;
		}
		Map<Program.Stage, String> merged = new LinkedHashMap<>(existing.sources());
		merged.putAll(sources);
		programs.put(name, new Program(name, merged));
	}

	private Optional<String> read(Includes prepared, String name, Program.Stage stage) {
		String path = path(name, stage);
		if (prepared.node(path).isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(prepared.expand(path).text());
		} catch (IncludeException exception) {
			return Optional.empty();
		}
	}

	private String path(String name, Program.Stage stage) {
		String prefix = dimension.isEmpty() ? "/" : "/" + dimension + "/";
		return prefix + name + stage.extension();
	}

	private boolean enabled(String name, ShaderProperties properties, Map<String, String> optionMacros,
							Set<String> disabledPrograms) {
		String identifier = dimension.isEmpty() ? name : dimension + "/" + name;
		if (disabledPrograms.contains(name) || disabledPrograms.contains(identifier)) {
			return false;
		}

		String condition = properties.programEnabled().get(identifier);
		if (condition == null) {
			condition = properties.programEnabled().get(name);
		}
		if (condition == null) {
			return true;
		}

		String trimmed = condition.trim();
		if (trimmed.isEmpty()) {
			return true;
		}
		return PropertiesPreprocessor.evaluate(trimmed, optionMacros);
	}

	public String dimension() {
		return dimension;
	}

	public Map<String, Program> programs() {
		return programs;
	}

	public Optional<Program> program(String name) {
		return Optional.ofNullable(programs.get(name));
	}

	public Optional<Program> resolve(ProgramId id) {
		ProgramId current = id;
		while (current != null) {
			Program program = programs.get(current.sourceName());
			if (program != null) {
				if (program.isRenderProgram()) {
					return Optional.of(program);
				}
				if (program.hasCompleteTessellationPair() || program.source(Program.Stage.TESS_CONTROL).isPresent()
					|| program.source(Program.Stage.TESS_EVALUATION).isPresent()) {
					return Optional.empty();
				}
			}
			current = current.fallback().orElse(null);
		}
		return Optional.empty();
	}

	public Optional<Program> resolve(String sourceName) {
		return ProgramId.fromSourceName(sourceName).flatMap(this::resolve);
	}

	public Optional<Program> resolve(DhProgramKind kind) {
		ProgramId id = switch (kind) {
			case TERRAIN -> ProgramId.DhTerrain;
			case WATER -> ProgramId.DhWater;
			case GENERIC -> ProgramId.DhGeneric;
			case SHADOW -> ProgramId.DhShadow;
		};
		return resolveDh(id);
	}

	private Optional<Program> resolveDh(ProgramId id) {
		ProgramId current = id;
		while (current != null) {
			Program program = programs.get(current.sourceName());
			if (program != null) {
				if (program.isRenderProgram()) {
					return Optional.of(program);
				}
				return Optional.empty();
			}
			current = current.fallback().orElse(null);
		}
		return Optional.empty();
	}

	public boolean hasDhProgram() {
		return programs.keySet().stream().anyMatch(name ->
			name.equals(ProgramId.DhTerrain.sourceName())
				|| name.equals(ProgramId.DhWater.sourceName())
				|| name.equals(ProgramId.DhGeneric.sourceName())
				|| name.equals(ProgramId.DhShadow.sourceName()));
	}

	public List<Program> tessellatedPrograms() {
		return programs.values().stream().filter(Program::isTessellatedRenderProgram).toList();
	}

	public List<Program> ordinaryRenderPrograms() {
		return programs.values().stream().filter(Program::isOrdinaryRenderProgram).toList();
	}

	public boolean hasRenderProgram(String name) {
		Program program = programs.get(name);
		return program != null && program.isRenderProgram();
	}

	public long computeProgramCount() {
		return programs.values().stream().filter(Program::isComputeProgram).count();
	}
}
