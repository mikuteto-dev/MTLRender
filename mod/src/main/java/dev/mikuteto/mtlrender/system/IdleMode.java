package dev.mikuteto.mtlrender.system;

import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.tune.SystemTune;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

public final class IdleMode {
	private static FrameState previous;
	private static int sameFrames;
	private static long nextCheck;
	private static boolean sleeping;
	private static boolean rendering = true;
	private static boolean decided;

	private IdleMode() {
	}

	public static void beginFrame(Minecraft minecraft, boolean advanceGameTime) {
		rendering = true;
		decided = false;
		if (!advanceGameTime) {
			wake();
		}
	}

	public static boolean shouldRender(Minecraft minecraft, boolean advanceGameTime) {
		if (decided) {
			return rendering;
		}
		decided = true;
		if (SystemTune.stopUnfocused(minecraft)) {
			rendering = false;
			return false;
		}
		Config.SystemConfig config = Config.get().system;
		if (!config.idle || !advanceGameTime || minecraft.level == null) {
			rendering = true;
			return true;
		}
		FrameState current = FrameState.capture(minecraft);
		long now = System.nanoTime();
		if (sleeping) {
			if (!current.same(previous)) {
				wake();
				previous = current;
				sameFrames = 1;
				rendering = true;
				return true;
			}
			if (now >= nextCheck) {
				nextCheck = now + checkNanos(config);
				rendering = true;
				return true;
			}
			rendering = false;
			return false;
		}
		if (previous == null || !current.same(previous)) {
			previous = current;
			sameFrames = 1;
		} else {
			sameFrames++;
		}
		if (sameFrames >= Math.max(1, config.idleFrames)) {
			sleeping = true;
			nextCheck = now + checkNanos(config);
		}
		rendering = true;
		return true;
	}

	public static boolean rendering() {
		return rendering;
	}

	public static void wake() {
		previous = null;
		sameFrames = 0;
		sleeping = false;
		nextCheck = 0;
	}

	private static long checkNanos(Config.SystemConfig config) {
		return Math.max(1, config.idleCheckMs) * 1_000_000L;
	}

	private record FrameState(
		Object screen,
		Object overlay,
		Object entity,
		double x,
		double y,
		double z,
		float xRot,
		float yRot,
		float fov,
		int width,
		int height,
		boolean initialized
	) {
		static FrameState capture(Minecraft minecraft) {
			Camera camera = minecraft.gameRenderer.mainCamera();
			return new FrameState(
				minecraft.gui.screen(),
				minecraft.gui.overlay(),
				camera.entity(),
				camera.position().x,
				camera.position().y,
				camera.position().z,
				camera.xRot(),
				camera.yRot(),
				camera.getFov(),
				minecraft.getWindow().getWidth(),
				minecraft.getWindow().getHeight(),
				camera.isInitialized()
			);
		}

		boolean same(FrameState other) {
			return other != null
				&& screen == other.screen
				&& overlay == other.overlay
				&& entity == other.entity
				&& x == other.x
				&& y == other.y
				&& z == other.z
				&& xRot == other.xRot
				&& yRot == other.yRot
				&& fov == other.fov
				&& width == other.width
				&& height == other.height
				&& initialized == other.initialized;
		}
	}
}
