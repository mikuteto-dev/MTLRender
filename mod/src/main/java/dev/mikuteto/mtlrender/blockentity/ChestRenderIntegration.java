package dev.mikuteto.mtlrender.blockentity;

public final class ChestRenderIntegration {
	private static final ThreadLocal<Boolean> DYNAMIC_ONLY = ThreadLocal.withInitial(() -> false);

	private ChestRenderIntegration() {
	}

	public static void enter(boolean dynamicOnly) {
		DYNAMIC_ONLY.set(dynamicOnly);
	}

	public static void exit() {
		DYNAMIC_ONLY.remove();
	}

	public static boolean dynamicOnly() {
		return DYNAMIC_ONLY.get();
	}
}
