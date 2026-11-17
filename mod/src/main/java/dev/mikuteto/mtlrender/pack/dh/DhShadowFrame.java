package dev.mikuteto.mtlrender.pack.dh;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DhShadowFrame {
	public record PackIdentity(String id) {
		public PackIdentity {
			Objects.requireNonNull(id, "pack id");
			if (id.isBlank()) throw new IllegalArgumentException("pack id must not be blank");
		}
	}

	private static final long DISABLED_GENERATION = Long.MIN_VALUE;
	private final boolean enabled;
	private final Optional<PackIdentity> pack;
	private final long generation;
	private final boolean shadowProgramAvailable;
	private final boolean shadowTerrain;
	private final boolean shadowTranslucent;
	private final DhUniformState uniforms;
	private final long uniformGeneration;
	private final long targetGeneration;
	private final long depthGeneration;
	private final DhDepthResources depthResources;

	private DhShadowFrame(boolean enabled, Optional<PackIdentity> pack, long generation, boolean shadowProgramAvailable,
		boolean shadowTerrain, boolean shadowTranslucent, DhUniformState uniforms, long uniformGeneration,
		long targetGeneration, long depthGeneration, DhDepthResources depthResources) {
		this.enabled = enabled;
		this.pack = pack;
		this.generation = generation;
		this.shadowProgramAvailable = shadowProgramAvailable;
		this.shadowTerrain = shadowTerrain;
		this.shadowTranslucent = shadowTranslucent;
		this.uniforms = uniforms;
		this.uniformGeneration = uniformGeneration;
		this.targetGeneration = targetGeneration;
		this.depthGeneration = depthGeneration;
		this.depthResources = Objects.requireNonNull(depthResources, "depth resources");
	}

	public static DhShadowFrame disabled() {
		return new DhShadowFrame(false, Optional.empty(), DISABLED_GENERATION, false, false, false,
			null, DISABLED_GENERATION, DISABLED_GENERATION, DISABLED_GENERATION, DhDepthResources.of(Map.of()));
	}

	public static DhShadowFrame enabled(PackIdentity pack, long generation, boolean shadowProgramAvailable,
		boolean shadowTerrain, boolean shadowTranslucent, DhUniformState uniforms, long uniformGeneration,
		long targetGeneration, DhDepthResources depthResources) {
		Objects.requireNonNull(depthResources, "depth resources");
		return enabled(pack, generation, shadowProgramAvailable, shadowTerrain, shadowTranslucent, uniforms,
			uniformGeneration, targetGeneration, depthResources.generation(), depthResources);
	}

	public static DhShadowFrame enabled(PackIdentity pack, long generation, boolean shadowProgramAvailable,
		boolean shadowTerrain, boolean shadowTranslucent, DhUniformState uniforms, long uniformGeneration,
		long targetGeneration, long depthGeneration) {
		return enabled(pack, generation, shadowProgramAvailable, shadowTerrain, shadowTranslucent, uniforms,
			uniformGeneration, targetGeneration, depthGeneration, DhDepthResources.of(depthGeneration, Map.of()));
	}

	private static DhShadowFrame enabled(PackIdentity pack, long generation, boolean shadowProgramAvailable,
		boolean shadowTerrain, boolean shadowTranslucent, DhUniformState uniforms, long uniformGeneration,
		long targetGeneration, long depthGeneration, DhDepthResources depthResources) {
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(uniforms, "uniforms");
		if (generation < 0L || uniformGeneration < 0L || targetGeneration < 0L || depthGeneration < 0L) {
			throw new IllegalArgumentException("DH generations must be non-negative");
		}
		if (generation != uniformGeneration || generation != targetGeneration || generation != depthGeneration
			|| uniforms.generation() != generation) {
			throw new IllegalArgumentException("DH shadow frame generations do not match");
		}
		if (uniforms.cameraKind() != DhUniformState.CameraKind.SHADOW) {
			throw new IllegalArgumentException("DH shadow frame requires shadow camera uniforms");
		}
		return new DhShadowFrame(true, Optional.of(pack), generation, shadowProgramAvailable, shadowTerrain,
			shadowTranslucent, uniforms, uniformGeneration, targetGeneration, depthGeneration, depthResources);
	}

	public boolean enabled() { return enabled; }
	public boolean hasPack() { return pack.isPresent(); }
	public Optional<PackIdentity> pack() { return pack; }
	public long generation() { return generation; }
	public boolean shadowProgramAvailable() { return shadowProgramAvailable; }
	public boolean shadowTerrain() { return shadowTerrain; }
	public boolean shadowTranslucent() { return shadowTranslucent; }
	public Optional<DhUniformState> uniforms() { return Optional.ofNullable(uniforms); }
	public long uniformGeneration() { return uniformGeneration; }
	public long targetGeneration() { return targetGeneration; }
	public long depthGeneration() { return depthGeneration; }
	public DhDepthResources depthResources() { return depthResources; }

	public boolean canRenderTerrain() {
		return enabled && shadowProgramAvailable && shadowTerrain;
	}

	public boolean canRenderTranslucent() {
		return enabled && shadowProgramAvailable && shadowTranslucent;
	}
}
