package dev.mikuteto.mtlrender.backend;

import java.util.OptionalDouble;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

final class MetalSampler extends GpuSampler {
	private static final double UNBOUNDED_LOD = 1000.0;
	private static final float MIN_LOD = 0.25F;

	private final NativeGpu gpu;
	private final long handle;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private boolean closed;

	MetalSampler(NativeGpu gpu, AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy,
		OptionalDouble maxLod) {
		this.gpu = gpu;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
		this.handle = gpu.createSampler(new int[] {
			addressModeU == AddressMode.REPEAT ? 0 : 1,
			addressModeV == AddressMode.REPEAT ? 0 : 1,
			minFilter == FilterMode.LINEAR ? 1 : 0,
			magFilter == FilterMode.LINEAR ? 1 : 0,
			maxAnisotropy,
			Float.floatToRawIntBits(Math.max(MIN_LOD, (float) maxLod.orElse(UNBOUNDED_LOD)))
		});
		if (handle == 0) {
			throw new IllegalStateException("Failed to create Metal sampler");
		}
	}

	static long handle(GpuSampler sampler) {
		return ((MetalSampler) sampler).handle;
	}

	boolean closed() {
		return closed;
	}

	@Override
	public AddressMode getAddressModeU() {
		return addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return maxLod;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			gpu.destroy(NativeGpu.SAMPLER, handle);
		}
	}
}
