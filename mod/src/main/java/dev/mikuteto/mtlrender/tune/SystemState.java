package dev.mikuteto.mtlrender.tune;

import dev.mikuteto.mtlrender.ffi.Mtlr;

public record SystemState(int thermalState, boolean lowPowerMode) {
	public static SystemState read() {
		return new SystemState(Mtlr.mtlr_thermal_state(), Mtlr.mtlr_low_power_mode());
	}
}
