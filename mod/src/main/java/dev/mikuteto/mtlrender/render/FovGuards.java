package dev.mikuteto.mtlrender.render;

public final class FovGuards {
	private FovGuards() {
	}

	public static boolean skipNeutralModifier(final float effectScale, final boolean scoping) {
		return effectScale == 0.0F && !scoping;
	}
}
