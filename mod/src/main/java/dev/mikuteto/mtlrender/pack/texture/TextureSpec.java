package dev.mikuteto.mtlrender.pack.texture;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public record TextureSpec(
	String sampler,
	TextureStage stage,
	Kind kind,
	Target target,
	int width,
	int height,
	int depth,
	String internalFormat,
	String pixelFormat,
	String pixelType,
	boolean blur,
	boolean clamp,
	byte[] pixels,
	Optional<TextureReference> reference) {
	public TextureSpec {
		Objects.requireNonNull(sampler);
		Objects.requireNonNull(kind);
		Objects.requireNonNull(target);
		Objects.requireNonNull(pixels);
		Objects.requireNonNull(reference);
		pixels = Arrays.copyOf(pixels, pixels.length);
	}

	@Override
	public byte[] pixels() {
		return Arrays.copyOf(pixels, pixels.length);
	}

	public boolean hasPixels() {
		return pixels.length > 0;
	}

	public enum Kind {
		PNG,
		RAW,
		NOISE,
		REFERENCE
	}

	public enum Target {
		TEXTURE_1D,
		TEXTURE_2D,
		TEXTURE_3D
	}
}
