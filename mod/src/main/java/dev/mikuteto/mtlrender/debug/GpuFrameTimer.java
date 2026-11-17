package dev.mikuteto.mtlrender.debug;

import java.util.ArrayDeque;
import java.util.OptionalLong;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.LongArrayList;

final class GpuFrameTimer {
	private static final int SLOTS = 16;
	private static final int MAX_AGE = SLOTS - 2;

	private record Pending(long frame, int slot) {
	}

	private final GpuDevice device;
	private final GpuQueryPool pool;
	private final float period;
	private final ArrayDeque<Pending> pending = new ArrayDeque<>();
	private long frame;
	private int slot = -1;

	private GpuFrameTimer(GpuDevice device) {
		this.device = device;
		this.pool = device.createTimestampQueryPool(SLOTS * 2);
		this.period = device.getDeviceInfo().timestampPeriod();
	}

	static GpuFrameTimer create() {
		return new GpuFrameTimer(RenderSystem.getDevice());
	}

	void beginFrame() {
		if (slot >= 0 || pending.size() >= MAX_AGE) {
			return;
		}
		slot = (int) (frame % SLOTS);
		device.createCommandEncoder().writeTimestamp(pool, slot * 2);
	}

	void endFrame() {
		if (slot < 0) {
			return;
		}
		device.createCommandEncoder().writeTimestamp(pool, slot * 2 + 1);
		pending.addLast(new Pending(frame, slot));
		slot = -1;
		frame++;
	}

	void collect(LongArrayList gpuNanos) {
		while (!pending.isEmpty()) {
			Pending oldest = pending.peekFirst();
			OptionalLong[] values = pool.getValues(oldest.slot() * 2, 2);
			if (values[0].isPresent() && values[1].isPresent()) {
				pending.pollFirst();
				long ticks = values[1].getAsLong() - values[0].getAsLong();
				if (ticks > 0) {
					gpuNanos.add((long) (ticks * (double) period));
				}
			} else if (frame - oldest.frame() >= MAX_AGE) {
				pending.pollFirst();
			} else {
				return;
			}
		}
	}
}
