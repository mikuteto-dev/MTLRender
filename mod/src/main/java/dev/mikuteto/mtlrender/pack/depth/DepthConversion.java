package dev.mikuteto.mtlrender.pack.depth;

public final class DepthConversion {
	private DepthConversion() {
	}

	public static float gameToPack(float depth) {
		if (!Float.isFinite(depth) || depth < 0.0f || depth > 1.0f) {
			throw new IllegalArgumentException("depth must be finite and within 0..1");
		}
		return 1.0f - depth;
	}

	public static boolean isDepthTexture(String name) {
		return canonical(name) != null;
	}

	public static String canonical(String name) {
		if (name == null) return null;
		return switch (name) {
			case "depthtex0", "gdepth", "gdepthtex" -> "depthtex0";
			case "depthtex1" -> "depthtex1";
			case "depthtex2" -> "depthtex2";
			case "shadow", "watershadow", "shadowtex", "shadowtex0", "shadowtex0HW", "shadowtex0DH" -> "shadowtex0";
			case "shadowtex1", "shadowtex1HW", "shadowtex1DH" -> "shadowtex1";
			default -> null;
		};
	}
}
