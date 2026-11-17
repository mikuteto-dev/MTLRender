package dev.mikuteto.mtlrender.backend;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.List;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.tune.Calibration;

public final class MetalSurface implements GpuSurfaceBackend {
	private static final List<GpuSurface.PresentMode> PRESENT_MODES = List.of(
		GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE
	);
	private static final boolean EVALUATION = Boolean.getBoolean("mtlrender.eval");
	private static final int EVALUATION_REFRESH = evaluationRefresh();
	private static final int UNSYNCED_MODE = "offscreen".equals(Config.get().present) ? NativeGpu.PRESENT_OFFSCREEN : NativeGpu.PRESENT_IMMEDIATE;

	private final NativeGpu gpu;
	private final MemorySegment handle;

	MetalSurface(NativeGpu gpu, MemorySegment handle) {
		this.gpu = gpu;
		this.handle = handle;
	}

	@Override
	public void configure(GpuSurface.Configuration configuration) {
		int mode = EVALUATION ? NativeGpu.PRESENT_FIFO : switch (configuration.presentMode()) {
			case FIFO, FIFO_RELAXED -> NativeGpu.PRESENT_FIFO;
			case IMMEDIATE -> UNSYNCED_MODE;
			default -> UNSYNCED_MODE;
		};
		MetalAccess.setPresentMode(mode);
		gpu.configureSurface(handle, configuration.width(), configuration.height(), mode, EVALUATION_REFRESH);
		if (configuration.width() > 0 && configuration.height() > 0) {
			Calibration.start(gpu.hardware(), gpu.name(), configuration.width(), configuration.height(), gpu::calibrate);
		}
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	public boolean visible() {
		return gpu.surfaceVisible(handle);
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView view) {
		((MetalCommandEncoder) encoder).blitToSurface(handle, view);
	}

	@Override
	public void present() {
	}

	@Override
	public void close() {
		gpu.destroySurface(handle);
	}

	private static int evaluationRefresh() {
		if (!EVALUATION) {
			return 0;
		}
		int refresh = Integer.getInteger("mtlrender.eval.refresh", 0);
		return refresh == 60 || refresh == 120 ? refresh : 0;
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return PRESENT_MODES;
	}
}
