package dev.mikuteto.mtlrender.particle;

import java.util.IdentityHashMap;
import java.util.Map;

import dev.mikuteto.mtlrender.particle.ParticleInstanceLayout.ParticleInstance;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

public final class ParticleInstanceStore {
	private static final Map<QuadParticleRenderState, Map<SingleQuadParticle.Layer, ParticleInstanceStream>> STATES = new IdentityHashMap<>();
	private static final Map<QuadParticleRenderState, ParticleStateVersion> VERSIONS = new IdentityHashMap<>();

	private ParticleInstanceStore() {
	}

	public static void add(
		QuadParticleRenderState state,
		SingleQuadParticle.Layer layer,
		float x, float y, float z,
		float xRot, float yRot, float zRot, float wRot,
		float scale, float u0, float u1, float v0, float v1,
		int color, int lightCoords
	) {
		Map<SingleQuadParticle.Layer, ParticleInstanceStream> layers = STATES.computeIfAbsent(state, ignored -> new IdentityHashMap<>());
		VERSIONS.computeIfAbsent(state, ignored -> new ParticleStateVersion()).changed();
		layers.computeIfAbsent(layer, ignored -> new ParticleInstanceStream()).add(new ParticleInstance(
			x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords
		));
	}

	public static long version(QuadParticleRenderState state) {
		ParticleStateVersion version = VERSIONS.get(state);
		return version == null ? 0L : version.value();
	}

	public static Map<SingleQuadParticle.Layer, ParticleInstanceStream> layers(QuadParticleRenderState state) {
		Map<SingleQuadParticle.Layer, ParticleInstanceStream> layers = STATES.get(state);
		return layers == null ? Map.of() : Map.copyOf(layers);
	}

	public static void clear(QuadParticleRenderState state) {
		Map<SingleQuadParticle.Layer, ParticleInstanceStream> layers = STATES.get(state);
		if (layers != null) {
			layers.values().forEach(ParticleInstanceStream::clear);
			VERSIONS.computeIfAbsent(state, ignored -> new ParticleStateVersion()).changed();
		}
	}
}
