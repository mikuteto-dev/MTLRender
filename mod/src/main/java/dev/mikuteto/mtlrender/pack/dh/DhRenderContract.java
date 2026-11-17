package dev.mikuteto.mtlrender.pack.dh;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DhRenderContract {
	public enum MaterialKind {
		TERRAIN,
		WATER,
		GENERIC
	}

	public record WorldOrigin(int x, int y, int z) {
	}

	private static final Set<DhVertexAttribute.Identity> TERRAIN_ATTRIBUTES = Set.of(
		DhVertexAttribute.Identity.POSITION,
		DhVertexAttribute.Identity.COLOR,
		DhVertexAttribute.Identity.EXTRA);
	private static final Set<DhVertexAttribute.Identity> GENERIC_ATTRIBUTES = Set.of(
		DhVertexAttribute.Identity.POSITION,
		DhVertexAttribute.Identity.COLOR,
		DhVertexAttribute.Identity.SCALE,
		DhVertexAttribute.Identity.TRANSLATE_CHUNK,
		DhVertexAttribute.Identity.TRANSLATE_SUB_CHUNK,
		DhVertexAttribute.Identity.MATERIAL);

	private final DhProgramKind programKind;
	private final int lodLayer;
	private final MaterialKind materialKind;
	private final List<DhVertexAttribute> attributes;
	private final WorldOrigin worldOrigin;
	private final boolean shadow;

	public DhRenderContract(
		DhProgramKind programKind,
		int lodLayer,
		MaterialKind materialKind,
		List<DhVertexAttribute> attributes,
		WorldOrigin worldOrigin,
		boolean shadow) {
		this.programKind = Objects.requireNonNull(programKind, "programKind");
		if (lodLayer < 0 || lodLayer > 4) {
			throw new IllegalArgumentException("LOD layer must be between 0 and 4");
		}
		this.lodLayer = lodLayer;
		this.materialKind = Objects.requireNonNull(materialKind, "materialKind");
		this.attributes = validateAttributes(attributes);
		this.worldOrigin = Objects.requireNonNull(worldOrigin, "worldOrigin");
		if (shadow != programKind.isShadow()) {
			throw new IllegalArgumentException("shadow classification does not match program kind");
		}
		if (programKind == DhProgramKind.GENERIC && materialKind != MaterialKind.GENERIC) {
			throw new IllegalArgumentException("generic DH programs require generic material");
		}
		if (programKind != DhProgramKind.GENERIC && materialKind == MaterialKind.GENERIC) {
			throw new IllegalArgumentException("terrain DH programs require terrain or water material");
		}
		validateRequiredAttributes(programKind, this.attributes);
		this.shadow = shadow;
	}

	public DhProgramKind programKind() {
		return programKind;
	}

	public int lodLayer() {
		return lodLayer;
	}

	public MaterialKind materialKind() {
		return materialKind;
	}

	public List<DhVertexAttribute> attributes() {
		return attributes;
	}

	public WorldOrigin worldOrigin() {
		return worldOrigin;
	}

	public boolean shadow() {
		return shadow;
	}

	private static List<DhVertexAttribute> validateAttributes(List<DhVertexAttribute> attributes) {
		Objects.requireNonNull(attributes, "attributes");
		EnumSet<DhVertexAttribute.Identity> identities = EnumSet.noneOf(DhVertexAttribute.Identity.class);
		for (DhVertexAttribute attribute : attributes) {
			DhVertexAttribute value = Objects.requireNonNull(attribute, "attributes must not contain null");
			if (!identities.add(value.identity())) {
				throw new IllegalArgumentException("duplicate DH vertex attribute: " + value.name());
			}
		}
		if (identities.isEmpty()) {
			throw new IllegalArgumentException("DH vertex attributes must not be empty");
		}
		return List.copyOf(attributes);
	}

	private static void validateRequiredAttributes(DhProgramKind programKind, List<DhVertexAttribute> attributes) {
		Set<DhVertexAttribute.Identity> required = programKind == DhProgramKind.GENERIC
			? GENERIC_ATTRIBUTES
			: TERRAIN_ATTRIBUTES;
		EnumSet<DhVertexAttribute.Identity> actual = EnumSet.noneOf(DhVertexAttribute.Identity.class);
		for (DhVertexAttribute attribute : attributes) {
			actual.add(attribute.identity());
		}
		if (!actual.containsAll(required)) {
			throw new IllegalArgumentException("missing required DH vertex attributes for " + programKind);
		}
	}
}
