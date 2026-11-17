package dev.mikuteto.mtlrender.tune;

import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.config.Config;
import net.minecraft.client.Minecraft;

public final class SystemTune {
	private static volatile SystemState state = new SystemState(0, false);
	private static boolean reducedRenderDistance;

	private SystemTune() {
	}

	public static void poll(Minecraft minecraft) {
		if (!MetalAccess.active()) {
			return;
		}
		Config.SystemConfig config = Config.get().system;
		if (config.thermalTuning || config.lowPowerTuning) {
			state = SystemState.read();
		}
		int pressure = MetalAccess.memoryPressure();
		if ((pressure & 2) != 0 || (pressure & 4) != 0) {
			if (config.memoryPressureCacheDrop) {
				MetalAccess.dropCaches();
			}
			if ((pressure & 4) != 0 && config.memoryPressureReduceRenderDistance && !reducedRenderDistance) {
				int distance = minecraft.options.renderDistance().get();
				minecraft.options.renderDistance().set(Math.max(2, distance - 4));
				reducedRenderDistance = true;
			}
		}
	}

	public static SystemState state() {
		return state;
	}

	public static int unfocusedFps(Minecraft minecraft) {
		Config.SystemConfig config = Config.get().system;
		return !minecraft.isWindowActive() && !minecraft.getWindow().isMinimized() ? Math.max(0, config.unfocusedFps) : -1;
	}

	public static boolean stopUnfocused(Minecraft minecraft) {
		return unfocusedFps(minecraft) == 0;
	}
}
