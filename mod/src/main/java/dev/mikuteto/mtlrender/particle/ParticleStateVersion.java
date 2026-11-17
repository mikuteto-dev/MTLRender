package dev.mikuteto.mtlrender.particle;

public final class ParticleStateVersion {
	private long value;

	public void changed() {
		this.value++;
	}

	public long value() {
		return this.value;
	}
}
