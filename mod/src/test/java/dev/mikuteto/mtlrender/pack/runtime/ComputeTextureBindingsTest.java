package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.texture.TextureReference;
import dev.mikuteto.mtlrender.pack.texture.TextureSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeTextureBindingsTest {
	@Test
	void preservesSamplerStateInPlans() {
		TextureSpec spec = new TextureSpec("sample", null, TextureSpec.Kind.PNG, TextureSpec.Target.TEXTURE_2D,
			2, 3, 1, "RGBA8", "RGBA", "UNSIGNED_BYTE", true, true, new byte[24], Optional.empty());
		ComputeTextureBindings.UploadPlan plan = ComputeTextureBindings.plan(spec);
		assertTrue(plan.blur());
		assertTrue(plan.clamp());
		assertEquals(8, plan.rowStride());
		assertEquals(24, plan.sliceStride());
	}

	@Test
	void reportsMissingReferenceResource() {
		TextureSpec spec = new TextureSpec("vanilla", null, TextureSpec.Kind.REFERENCE, TextureSpec.Target.TEXTURE_2D,
			0, 0, 0, null, null, null, false, false, new byte[0],
			Optional.of(new TextureReference("minecraft", "textures/missing.png", false)));
		ComputeTextureBindings.Factory unavailable = new ComputeTextureBindings.Factory() {
			@Override public com.mojang.blaze3d.textures.GpuTexture createTexture(com.mojang.blaze3d.GpuFormat format, int width, int height, int depth, String label) { throw new AssertionError(); }
			@Override public com.mojang.blaze3d.textures.GpuTextureView createView(com.mojang.blaze3d.textures.GpuTexture texture) { throw new AssertionError(); }
			@Override public com.mojang.blaze3d.textures.GpuSampler createSampler(boolean blur, boolean clamp) { throw new AssertionError(); }
			@Override public void write(com.mojang.blaze3d.textures.GpuTexture texture, java.nio.ByteBuffer pixels, int depth, int width, int height) { throw new AssertionError(); }
		};
		assertThrows(IOException.class, () -> new ComputeTextureBindings(List.of(spec), unavailable, reference -> {
			throw new IOException("missing resource");
		}));
	}
}
