package dev.mikuteto.mtlrender.blockentity;

import java.util.Arrays;
import java.util.Objects;

public record StaticBlockEntityBakePart(String partKey, byte[] geometryPayload, String materialKey) {
	public StaticBlockEntityBakePart {
		partKey = requireKey(partKey, "partKey");
		Objects.requireNonNull(geometryPayload, "geometryPayload");
		geometryPayload = geometryPayload.clone();
		materialKey = requireKey(materialKey, "materialKey");
	}

	@Override
	public byte[] geometryPayload() {
		return geometryPayload.clone();
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof StaticBlockEntityBakePart other)) {
			return false;
		}
		return partKey.equals(other.partKey)
			&& Arrays.equals(geometryPayload, other.geometryPayload)
			&& materialKey.equals(other.materialKey);
	}

	@Override
	public int hashCode() {
		int result = partKey.hashCode();
		result = 31 * result + Arrays.hashCode(geometryPayload);
		return 31 * result + materialKey.hashCode();
	}

	private static String requireKey(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
