package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

import java.util.Objects;
import java.util.Optional;

public final class GbufferDrawBridge {
	public record Draw(GbufferDrawState state, String uniformName, GpuBufferSlice uniformSlice) {
		public Draw {
			Objects.requireNonNull(state, "state");
			Objects.requireNonNull(uniformName, "uniform name");
			Objects.requireNonNull(uniformSlice, "uniform slice");
			if (uniformName.isBlank()) throw new IllegalArgumentException("uniform name must not be blank");
		}
	}

	@FunctionalInterface
	public interface Provider {
		Optional<Draw> current();
	}

	private static volatile Provider provider;

	private GbufferDrawBridge() {
	}

	public static void install(Provider next) {
		provider = Objects.requireNonNull(next, "provider");
		GbufferSemanticContext.clear();
	}

	public static void remove(Provider expected) {
		if (provider == expected) {
			provider = null;
			GbufferSemanticContext.clear();
		}
	}

	public static void beginFrame() {
		GbufferSemanticContext.beginFrame();
	}

	public static void endFrame() {
		GbufferSemanticContext.endFrame();
	}

	public static void clear() {
		provider = null;
		GbufferSemanticContext.clear();
	}

	public static Optional<Draw> current() {
		Provider active = provider;
		return active == null ? Optional.empty() : Objects.requireNonNull(active.current(), "provider result");
	}
}
