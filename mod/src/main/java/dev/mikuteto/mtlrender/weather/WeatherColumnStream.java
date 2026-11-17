package dev.mikuteto.mtlrender.weather;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.WeatherEffectRenderer;

public final class WeatherColumnStream {
	public static final int STRIDE = 32;
	private ByteBuffer rain = allocate();
	private ByteBuffer snow = allocate();
	private int rainCount;
	private int snowCount;

	public void reset() {
		rainCount = 0;
		snowCount = 0;
	}

	public void append(WeatherEffectRenderer.ColumnInstance column, boolean snow) {
		ByteBuffer target = snow ? this.snow : this.rain;
		int count = snow ? snowCount : rainCount;
		if (count == target.capacity() / STRIDE) {
			target = grow(target, count + 1);
			if (snow) {
				this.snow = target;
			} else {
				this.rain = target;
			}
		}
		int offset = count * STRIDE;
		target.putInt(offset, column.x());
		target.putInt(offset + 4, column.z());
		target.putInt(offset + 8, column.bottomY());
		target.putInt(offset + 12, column.topY());
		target.putFloat(offset + 16, column.uOffset());
		target.putFloat(offset + 20, column.vOffset());
		target.putInt(offset + 24, column.lightCoords());
		target.putInt(offset + 28, snow ? 1 : 0);
		if (snow) {
			snowCount++;
		} else {
			rainCount++;
		}
	}

	public ByteBuffer buffer() {
		ByteBuffer result = ByteBuffer.allocateDirect(count() * STRIDE).order(ByteOrder.nativeOrder());
		ByteBuffer rainCopy = buffer(false);
		ByteBuffer snowCopy = buffer(true);
		result.put(rainCopy);
		result.put(snowCopy);
		result.flip();
		return result.asReadOnlyBuffer().order(result.order());
	}

	public ByteBuffer buffer(boolean snow) {
		ByteBuffer source = snow ? this.snow : this.rain;
		int count = snow ? snowCount : rainCount;
		ByteBuffer result = source.duplicate().order(source.order());
		result.position(0);
		result.limit(count * STRIDE);
		return result.asReadOnlyBuffer().order(source.order());
	}

	public int count() {
		return rainCount + snowCount;
	}

	public int rainCount() {
		return rainCount;
	}

	public int snowCount() {
		return snowCount;
	}

	private static ByteBuffer allocate() {
		return ByteBuffer.allocateDirect(STRIDE * 256).order(ByteOrder.nativeOrder());
	}

	private static ByteBuffer grow(ByteBuffer source, int records) {
		int capacity = source.capacity();
		while (capacity < records * STRIDE) {
			capacity = Math.multiplyExact(capacity, 2);
		}
		ByteBuffer replacement = ByteBuffer.allocateDirect(capacity).order(source.order());
		ByteBuffer copy = source.duplicate();
		copy.position(0);
		copy.limit(source.capacity());
		replacement.put(copy);
		return replacement;
	}
}
