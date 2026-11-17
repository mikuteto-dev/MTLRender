package dev.mikuteto.mtlrender.terrain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.ids.IdMap;

public final class PackTerrainRequirements {
	public enum Mode {
		NONE,
		VANILLA,
		EXTRA
	}

	public enum Input {
		POSITION,
		COLOR,
		UV0,
		UV2,
		NORMAL,
		ENTITY,
		MID_TEX_COORD,
		TANGENT,
		MID_BLOCK
	}

	private static final Set<String> POSITION_NAMES = Set.of("Position", "gl_Vertex", "mtlr_vertex");
	private static final Set<String> COLOR_NAMES = Set.of("Color", "gl_Color", "mtlr_color");
	private static final Set<String> UV0_NAMES = Set.of("UV0", "gl_MultiTexCoord0", "mtlr_texcoord0");
	private static final Set<String> UV2_NAMES = Set.of("UV2", "gl_MultiTexCoord2", "mtlr_texcoord2");
	private static final Set<String> NORMAL_NAMES = Set.of("Normal", "gl_Normal", "mtlr_normal");
	private static final Set<String> ENTITY_NAMES = Set.of("mc_Entity", "mtlr_entity");
	private static final Set<String> MID_TEX_NAMES = Set.of("mc_midTexCoord", "mtlr_mid_texcoord");
	private static final Set<String> TANGENT_NAMES = Set.of("at_tangent", "mtlr_tangent");
	private static final Set<String> MID_BLOCK_NAMES = Set.of("at_midBlock", "mtlr_mid_block");
	private static final PackTerrainRequirements NONE = new PackTerrainRequirements(false, false, EnumSet.noneOf(Input.class));

	private final boolean packActive;
	private final boolean blockIdMapAvailable;
	private final Set<Input> inputs;
	private final Mode mode;
	private final int sidecarMask;

	private PackTerrainRequirements(boolean packActive, boolean blockIdMapAvailable, Set<Input> inputs) {
		this.packActive = packActive;
		this.blockIdMapAvailable = blockIdMapAvailable;
		this.inputs = Set.copyOf(inputs);
		this.mode = !packActive ? Mode.NONE : inputs.stream().anyMatch(PackTerrainRequirements::isExtra)
			? Mode.EXTRA : Mode.VANILLA;
		int mask = 0;
		if (inputs.contains(Input.ENTITY)) mask |= PackTerrainLayout.Field.BLOCK_ID.bit();
		if (inputs.contains(Input.MID_TEX_COORD)) mask |= PackTerrainLayout.Field.MID_TEX_COORD.bit();
		if (inputs.contains(Input.NORMAL) || inputs.contains(Input.TANGENT)) mask |= PackTerrainLayout.Field.NORMAL_TANGENT.bit();
		if (inputs.contains(Input.MID_BLOCK)) mask |= PackTerrainLayout.Field.MID_BLOCK.bit();
		this.sidecarMask = mask;
	}

	public static PackTerrainRequirements none() {
		return NONE;
	}

	public static PackTerrainRequirements fromDeclaredInputs(Collection<String> names, IdMap idMap) {
		return fromDeclaredInputs(true, names, idMap);
	}

	public static PackTerrainRequirements fromDeclaredInputs(boolean packActive, Collection<String> names, IdMap idMap) {
		Objects.requireNonNull(names, "declared inputs");
		if (!packActive) {
			return NONE;
		}
		boolean mapAvailable = idMap != null && idMap.blockStateIds().length != 0;
		EnumSet<Input> inputs = EnumSet.noneOf(Input.class);
		for (String name : names) {
			if (name == null) continue;
			add(inputs, name, POSITION_NAMES, Input.POSITION);
			add(inputs, name, COLOR_NAMES, Input.COLOR);
			add(inputs, name, UV0_NAMES, Input.UV0);
			add(inputs, name, UV2_NAMES, Input.UV2);
			add(inputs, name, NORMAL_NAMES, Input.NORMAL);
			add(inputs, name, ENTITY_NAMES, Input.ENTITY);
			add(inputs, name, MID_TEX_NAMES, Input.MID_TEX_COORD);
			add(inputs, name, TANGENT_NAMES, Input.TANGENT);
			add(inputs, name, MID_BLOCK_NAMES, Input.MID_BLOCK);
		}
		return new PackTerrainRequirements(true, mapAvailable, inputs);
	}

	public static PackTerrainRequirements fromReflection(boolean packActive, Reflection reflection, IdMap idMap) {
		Objects.requireNonNull(reflection, "reflection");
		return fromDeclaredInputs(packActive, reflection.vertexInputs().stream().map(Reflection.VertexInput::name).toList(), idMap);
	}

	public boolean packActive() { return packActive; }
	public boolean blockIdMapAvailable() { return blockIdMapAvailable; }
	public Mode mode() { return mode; }
	public Set<Input> inputs() { return inputs; }
	public int sidecarMask() { return sidecarMask; }
	public boolean needsExtraData() { return mode == Mode.EXTRA; }
	public boolean isNoWork() { return this == NONE; }

	public long fingerprint() {
		long value = 0x9e3779b97f4a7c15L;
		value = Long.rotateLeft(value ^ mode.ordinal(), 17);
		value = Long.rotateLeft(value ^ sidecarMask, 23);
		for (Input input : Input.values()) {
			if (inputs.contains(input)) {
				value = Long.rotateLeft(value ^ (input.ordinal() + 1L), 11);
			}
		}
		return value;
	}

	private static boolean isExtra(Input input) {
		return input.ordinal() >= Input.NORMAL.ordinal();
	}

	private static void add(EnumSet<Input> inputs, String name, Set<String> names, Input input) {
		if (names.contains(name)) inputs.add(input);
	}
}
