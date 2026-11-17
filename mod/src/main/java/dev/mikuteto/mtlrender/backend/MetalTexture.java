package dev.mikuteto.mtlrender.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

final class MetalTexture extends GpuTexture {
	private final NativeGpu gpu;
	private final long handle;
	private final long accountedBytes;
	private boolean closed;

	MetalTexture(NativeGpu gpu, int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		this(gpu, usage, label, format, width, height, depthOrLayers, mipLevels, false);
	}

	MetalTexture(NativeGpu gpu, int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels, boolean shaderWrite) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.gpu = gpu;
		boolean cube = (usage & USAGE_CUBEMAP_COMPATIBLE) != 0;
		boolean renderTarget = (usage & USAGE_RENDER_ATTACHMENT) != 0;
		this.handle = gpu.createTexture(Formats.pixelFormat(format), width, height, depthOrLayers, mipLevels, cube, renderTarget, shaderWrite);
		if (handle == 0) {
			throw new IllegalStateException("Failed to create Metal texture " + label + " (" + format + " " + width + "x" + height + "x" + depthOrLayers + ")");
		}
		this.accountedBytes = textureBytes(width, height, depthOrLayers, mipLevels);
		gpu.accountTexture(accountedBytes);
	}

	static long handle(GpuTexture texture) {
		return ((MetalTexture) texture).handle;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			gpu.destroy(NativeGpu.TEXTURE, handle);
			gpu.releaseTexture(accountedBytes);
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	private static long textureBytes(int width, int height, int layers, int mips) {
		long total = 0;
		int w = Math.max(1, width);
		int h = Math.max(1, height);
		for (int mip = 0; mip < mips; mip++) {
			total = Math.addExact(total, Math.multiplyExact(Math.multiplyExact((long) w, h), Math.max(1, layers) * 4L));
			w = Math.max(1, w / 2);
			h = Math.max(1, h / 2);
		}
		return total;
	}
}
