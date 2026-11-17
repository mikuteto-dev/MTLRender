package dev.mikuteto.mtlrender.pack.dh;

import java.util.Arrays;
import java.util.Objects;

public final class DhVertexAttribute {
	public enum Type {
		UINT32_VEC4,
		FLOAT_VEC4,
		FLOAT_VEC3,
		INT32_VEC3,
		INT32
	}

	public enum Identity {
		POSITION("vPosition", Type.UINT32_VEC4, 0),
		COLOR("iris_color", Type.FLOAT_VEC4, 1),
		EXTRA("irisExtra", Type.UINT32_VEC4, 2),
		SCALE("aScale", Type.FLOAT_VEC3, 2),
		TRANSLATE_CHUNK("aTranslateChunk", Type.INT32_VEC3, 3),
		TRANSLATE_SUB_CHUNK("aTranslateSubChunk", Type.FLOAT_VEC3, 4),
		MATERIAL("aMaterial", Type.INT32, 5);

		private final String name;
		private final Type type;
		private final int location;

		Identity(String name, Type type, int location) {
			this.name = name;
			this.type = type;
			this.location = location;
		}

		public String attributeName() {
			return name;
		}

		public Type type() {
			return type;
		}

		public int location() {
			return location;
		}
	}

	private final Identity identity;

	public DhVertexAttribute(Identity identity) {
		this.identity = Objects.requireNonNull(identity, "identity");
	}

	public DhVertexAttribute(String name, Type type, int location) {
		this.identity = Arrays.stream(Identity.values())
			.filter(value -> value.name.equals(name) && value.type == type && value.location == location)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unknown DH vertex attribute: " + name));
	}

	public Identity identity() {
		return identity;
	}

	public String name() {
		return identity.attributeName();
	}

	public Type type() {
		return identity.type;
	}

	public int location() {
		return identity.location;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof DhVertexAttribute attribute && identity == attribute.identity;
	}

	@Override
	public int hashCode() {
		return identity.hashCode();
	}
}
