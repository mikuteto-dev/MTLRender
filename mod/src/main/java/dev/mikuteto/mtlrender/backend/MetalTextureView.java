package dev.mikuteto.mtlrender.backend;

import com.mojang.blaze3d.textures.GpuTextureView;

final class MetalTextureView extends GpuTextureView {
	private final NativeGpu gpu;
	private final long handle;
	private boolean closed;

	MetalTextureView(NativeGpu gpu, MetalTexture texture, int baseMipLevel, int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
		this.gpu = gpu;
		this.handle = gpu.createView(MetalTexture.handle(texture), baseMipLevel, mipLevels);
		if (handle == 0) {
			throw new IllegalStateException("Failed to create a view of " + texture.getLabel() + " (mips " + baseMipLevel + "+" + mipLevels + ")");
		}
	}

	static long handle(GpuTextureView view) {
		return ((MetalTextureView) view).handle;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			gpu.destroy(NativeGpu.TEXTURE, handle);
		}
	}

	@Override
	public boolean isClosed() {
		return closed || texture().isClosed();
	}
}
