package dev.mikuteto.mtlrender.culling;

import java.nio.ByteBuffer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class FeatureInstanceStream {
	public static final int STRIDE = 32;
	public static final int FLAG_BLOCK_ENTITY = 1;
	public static final int FLAG_OUTLINE = 1 << 1;
	public static final int FLAG_NAME_TAG = 1 << 2;
	public static final int FLAG_CAMERA = 1 << 3;
	public static final int FLAG_VEHICLE = 1 << 4;
	public static final int FLAG_SPECTATOR = 1 << 5;
	public static final int FLAG_SHADOW = 1 << 6;
	public static final int FLAG_FORCE_VISIBLE = 1 << 7;

	private final List<Instance> instances = new ArrayList<>();
	private ByteBuffer packed = ByteBuffer.allocate(STRIDE * 256).order(ByteOrder.nativeOrder());
	private boolean valid;
	private boolean[] visible = new boolean[0];
	private boolean[] pendingVisible = new boolean[0];
	private long frameId;
	private long maskRejected;
	private long forcedVisible;
	private boolean maskResultReady;
	private boolean maskRequested;
	private boolean failOpen;

	public void beginFrame() {
		this.instances.clear();
		this.packed.clear();
		this.valid = true;
		this.frameId++;
		this.pendingVisible = this.visible;
		this.visible = new boolean[0];
		this.maskRejected = 0L;
		this.forcedVisible = 0L;
		this.maskResultReady = false;
		this.maskRequested = false;
		this.failOpen = false;
	}

	public void activatePendingMask() {
		if (this.pendingVisible.length == this.instances.size()) {
			this.visible = this.pendingVisible;
			this.countMaskRejections();
			this.maskResultReady = true;
		} else {
			clearMask();
		}
		this.pendingVisible = new boolean[0];
	}

	public long frameId() {
		return this.frameId;
	}

	public void acceptPreviousMask(MemorySegment mask, int count) {
		this.acceptPreviousMask(mask, count, 0L, 0L);
	}

	public void acceptPreviousMask(MemorySegment mask, int count, long generation, long expectedGeneration) {
		if (generation != expectedGeneration || mask == null || count < 0 || count != this.instances.size()
			|| mask.byteSize() < (long) count * Integer.BYTES) {
			this.failOpen = true;
			clearMask();
			return;
		}
		this.visible = new boolean[count];
		for (int index = 0; index < count; index++) {
			int flags = this.instances.get(index).flags();
			this.visible[index] = (flags & FLAG_FORCE_VISIBLE) != 0
				|| mask.get(ValueLayout.JAVA_INT_UNALIGNED, (long) index * Integer.BYTES) != 0;
		}
		this.maskResultReady = true;
		this.countMaskRejections();
	}

	public void clearMask() {
		this.visible = new boolean[this.instances.size()];
		java.util.Arrays.fill(this.visible, true);
		this.pendingVisible = new boolean[0];
		this.forcedVisible = this.instances.stream().filter(Instance::forceVisible).count();
	}

	public boolean visible(int slot) {
		return slot < 0 || slot >= this.visible.length || this.visible[slot];
	}

	public boolean maskMatches(long frame) {
		return this.valid && this.frameId == frame && this.visible.length == this.instances.size();
	}

	public int add(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int flags) {
		int slot = this.instances.size();
		boolean boundsValid = finite(minX) && finite(minY) && finite(minZ) && finite(maxX) && finite(maxY) && finite(maxZ)
			&& minX <= maxX && minY <= maxY && minZ <= maxZ;
		int storedFlags = boundsValid ? flags : flags | FLAG_FORCE_VISIBLE;
		Instance instance = new Instance(slot, minX, minY, minZ, maxX, maxY, maxZ, storedFlags, boundsValid);
		this.instances.add(instance);
		if (instance.forceVisible()) {
			this.forcedVisible++;
		}
		ensureCapacity((slot + 1) * STRIDE);
		int offset = slot * STRIDE;
		this.packed.putFloat(offset, finite(minX) ? minX : 0.0F);
		this.packed.putFloat(offset + 4, finite(minY) ? minY : 0.0F);
		this.packed.putFloat(offset + 8, finite(minZ) ? minZ : 0.0F);
		this.packed.putFloat(offset + 12, finite(maxX) ? maxX : 0.0F);
		this.packed.putFloat(offset + 16, finite(maxY) ? maxY : 0.0F);
		this.packed.putFloat(offset + 20, finite(maxZ) ? maxZ : 0.0F);
		this.packed.putInt(offset + 24, slot);
		this.packed.putInt(offset + 28, storedFlags);
		return slot;
	}

	public void invalidate() {
		this.instances.clear();
		this.packed.clear();
		this.valid = false;
	}

	public boolean valid() {
		return this.valid;
	}

	public int count() {
		return this.instances.size();
	}

	public List<Instance> instances() {
		return List.copyOf(this.instances);
	}

	public ByteBuffer buffer() {
		ByteBuffer result = this.packed.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
		result.limit(this.instances.size() * STRIDE);
		result.position(0);
		return result;
	}

	private void ensureCapacity(int required) {
		if (required <= this.packed.capacity()) {
			return;
		}
		int capacity = this.packed.capacity();
		while (capacity < required) {
			capacity = Math.multiplyExact(capacity, 2);
		}
		ByteBuffer replacement = ByteBuffer.allocate(capacity).order(ByteOrder.nativeOrder());
		ByteBuffer current = this.packed.duplicate();
		current.clear();
		replacement.put(current);
		this.packed = replacement;
	}

	public void markMaskRequested() {
		this.maskRequested = true;
		this.maskResultReady = false;
	}

	public void markFailOpen() {
		this.failOpen = true;
		this.maskResultReady = false;
		this.clearMask();
	}

	public long maskRejected() {
		return this.maskRejected;
	}

	public long forcedVisible() {
		return this.forcedVisible;
	}

	public boolean maskRequested() {
		return this.maskRequested;
	}

	public boolean maskResultReady() {
		return this.maskResultReady;
	}

	public boolean failOpen() {
		return this.failOpen;
	}

	private void countMaskRejections() {
		this.maskRejected = 0L;
		for (int index = 0; index < this.visible.length; index++) {
			if (!this.visible[index] && !this.instances.get(index).forceVisible()) {
				this.maskRejected++;
			}
		}
	}

	private static boolean finite(float value) {
		return Float.isFinite(value);
	}

	public record Instance(int slot, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int flags, boolean boundsValid) {
		public boolean forceVisible() {
			return (this.flags & FLAG_FORCE_VISIBLE) != 0;
		}
	}
}
