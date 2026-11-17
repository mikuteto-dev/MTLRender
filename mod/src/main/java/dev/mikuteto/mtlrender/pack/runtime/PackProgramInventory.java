package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ProgramSet;
import dev.mikuteto.mtlrender.pack.ShaderPack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PackProgramInventory {
	private final List<PackProgramRequest> requests;

	public PackProgramInventory(String pack, ShaderPack shaderPack) {
		this.requests = enumerate(pack, shaderPack);
	}

	public static PackProgramInventory from(String pack, ShaderPack shaderPack) {
		return new PackProgramInventory(pack, shaderPack);
	}

	public static PackProgramInventory from(ShaderPack shaderPack) {
		return new PackProgramInventory("shaderpack", shaderPack);
	}

	public List<PackProgramRequest> requests() {
		return requests;
	}

	public List<PackProgramRequest> programs() {
		return requests;
	}

	private static List<PackProgramRequest> enumerate(String pack, ShaderPack shaderPack) {
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(shaderPack, "shader pack");
		List<PackProgramRequest> result = new ArrayList<>();
		List<Map.Entry<String, ProgramSet>> sets = new ArrayList<>(shaderPack.programSets().entrySet());
		sets.sort(Map.Entry.comparingByKey());
		for (Map.Entry<String, ProgramSet> setEntry : sets) {
			List<Program> programs = new ArrayList<>(setEntry.getValue().programs().values());
			programs.sort(Comparator.comparing(Program::name));
			for (Program program : programs) {
				if (!program.isRenderProgram()) {
					continue;
				}
				Map<Program.Stage, String> sources = program.sources().entrySet().stream()
					.filter(entry -> entry.getKey() != Program.Stage.COMPUTE)
					.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
						(left, right) -> left, java.util.LinkedHashMap::new));
				result.add(new PackProgramRequest(pack, setEntry.getKey(), program.name(), sources));
			}
		}
		return List.copyOf(result);
	}
}
