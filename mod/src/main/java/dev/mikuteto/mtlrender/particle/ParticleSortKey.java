package dev.mikuteto.mtlrender.particle;

public record ParticleSortKey(long stateVersion, double cameraX, double cameraY, double cameraZ, int layerIdentity, int textureIdentity) {
	public boolean matches(ParticleSortKey other) {
		return this.equals(other);
	}
}
