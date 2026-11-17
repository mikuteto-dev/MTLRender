package dev.mikuteto.mtlrender.particle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import dev.mikuteto.mtlrender.particle.ParticleInstanceLayout.ParticleInstance;

public final class ParticleInstanceStream {
	private ByteBuffer data = ByteBuffer.allocate(ParticleInstanceLayout.STRIDE * 1024).order(ByteOrder.nativeOrder());
	private int count;

	public void add(ParticleInstance instance) {
		int offset = this.count * ParticleInstanceLayout.STRIDE;
		this.ensureCapacity(offset + ParticleInstanceLayout.STRIDE);
		ParticleInstanceLayout.pack(this.data, offset, instance);
		this.count++;
	}

	public void clear() {
		this.count = 0;
	}

	public int count() {
		return this.count;
	}

	public ByteBuffer data() {
		ByteBuffer result = this.data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
		result.limit(this.count * ParticleInstanceLayout.STRIDE);
		result.position(0);
		return result;
	}

	private void ensureCapacity(int required) {
		if (required <= this.data.capacity()) {
			return;
		}
		int capacity = this.data.capacity();
		while (capacity < required) {
			capacity = Math.multiplyExact(capacity, 2);
		}
		ByteBuffer replacement = ByteBuffer.allocate(capacity).order(ByteOrder.nativeOrder());
		ByteBuffer current = this.data.duplicate();
		current.clear();
		replacement.put(current);
		this.data = replacement;
	}
}
