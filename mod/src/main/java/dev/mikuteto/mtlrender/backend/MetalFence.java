package dev.mikuteto.mtlrender.backend;

import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.buffers.GpuFence;

final class MetalFence implements GpuFence {
	private final NativeGpu gpu;
	private final long frame;
	private boolean completed;

	MetalFence(NativeGpu gpu, long frame) {
		this.gpu = gpu;
		this.frame = frame;
	}

	@Override
	public boolean awaitCompletion(long timeoutNS) {
		if (completed) {
			return true;
		}
		if (MetalAccess.lastCompletedFrames() > frame) {
			completed = true;
			return true;
		}
		if (timeoutNS <= 0) {
			long done = gpu.refreshCompletedFrames();
			MetalAccess.observeCompleted(done);
			completed = done > frame;
			return completed;
		}
		long done = gpu.refreshCompletedFrames();
		completed = done > frame
			|| gpu.submittedFrames() > frame && gpu.waitFrame(frame, toMillis(timeoutNS));
		if (completed) {
			MetalAccess.observeCompleted(gpu.refreshCompletedFrames());
		}
		return completed;
	}

	@Override
	public void close() {
		completed = true;
	}

	private static long toMillis(long timeoutNS) {
		long millis = TimeUnit.NANOSECONDS.toMillis(timeoutNS);
		return TimeUnit.MILLISECONDS.toNanos(millis) < timeoutNS ? millis + 1 : millis;
	}
}
