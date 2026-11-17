package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.ProgramId;

import java.util.Objects;

public enum ShadowProgramKind {
	TERRAIN_SOLID(ProgramId.ShadowSolid),
	TERRAIN_CUTOUT(ProgramId.ShadowCutout),
	TERRAIN_WATER(ProgramId.ShadowWater),
	ENTITIES(ProgramId.ShadowEntities),
	LIGHTNING(ProgramId.ShadowLightning),
	BLOCK_ENTITIES(ProgramId.ShadowBlock);

	private final ProgramId program;

	ShadowProgramKind(ProgramId program) {
		this.program = Objects.requireNonNull(program, "program");
	}

	public ProgramId program() {
		return program;
	}
}
