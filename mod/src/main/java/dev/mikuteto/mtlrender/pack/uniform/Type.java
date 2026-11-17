package dev.mikuteto.mtlrender.pack.uniform;

public enum Type {
	FLOAT("float", 1, false, false),
	INT("int", 1, true, false),
	BOOL("bool", 1, true, false),
	VEC2("vec2", 2, false, false),
	VEC3("vec3", 3, false, false),
	VEC4("vec4", 4, false, false),
	IVEC2("ivec2", 2, true, false),
	IVEC3("ivec3", 3, true, false),
	IVEC4("ivec4", 4, true, false),
	BVEC2("bvec2", 2, true, false),
	BVEC3("bvec3", 3, true, false),
	BVEC4("bvec4", 4, true, false),
	MAT2("mat2", 2, false, true),
	MAT3("mat3", 3, false, true),
	MAT4("mat4", 4, false, true);

	private final String shaderName;
	private final int components;
	private final boolean integral;
	private final boolean matrix;

	Type(String shaderName, int components, boolean integral, boolean matrix) {
		this.shaderName = shaderName;
		this.components = components;
		this.integral = integral;
		this.matrix = matrix;
	}

	public String shaderName() {
		return shaderName;
	}

	public int components() {
		return matrix ? components * components : components;
	}

	public int vectorWidth() {
		return components;
	}

	public boolean integral() {
		return integral;
	}

	public boolean matrix() {
		return matrix;
	}

	public int alignment() {
		if (matrix || components >= 3) {
			return 16;
		}
		return components == 2 ? 8 : 4;
	}

	public int size() {
		if (matrix) {
			return components * 16;
		}
		return components >= 3 ? 16 : components * 4;
	}

	public static Type parse(String name) {
		for (Type type : values()) {
			if (type.shaderName.equals(name)) {
				return type;
			}
		}
		throw new IllegalArgumentException("unknown uniform type: " + name);
	}
}
