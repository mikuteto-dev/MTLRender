package dev.mikuteto.mtlrender.pack.dh;

import java.util.Objects;

public final class DhShadowDraw {
	public enum CullingMode {
		NONE,
		FRONT,
		BACK,
		BOTH
	}

	private final long lodEntryIdentity;
	private final DhRenderContract contract;
	private final boolean shadow;
	private final CullingMode culling;
	private final long frameGeneration;

	public DhShadowDraw(long lodEntryIdentity, DhRenderContract contract, boolean shadow, CullingMode culling,
		long frameGeneration) {
		if (lodEntryIdentity < 0L) throw new IllegalArgumentException("LOD entry identity must be non-negative");
		this.contract = Objects.requireNonNull(contract, "contract");
		this.culling = Objects.requireNonNull(culling, "culling");
		if (contract.shadow() != shadow || contract.programKind().isShadow() != shadow) {
			throw new IllegalArgumentException("DH draw shadow classification does not match contract");
		}
		if (frameGeneration < 0L) throw new IllegalArgumentException("frame generation must be non-negative");
		this.lodEntryIdentity = lodEntryIdentity;
		this.shadow = shadow;
		this.frameGeneration = frameGeneration;
	}

	public long lodEntryIdentity() { return lodEntryIdentity; }
	public DhRenderContract contract() { return contract; }
	public DhProgramKind programKind() { return contract.programKind(); }
	public int lodLayer() { return contract.lodLayer(); }
	public DhRenderContract.MaterialKind materialKind() { return contract.materialKind(); }
	public boolean shadow() { return shadow; }
	public CullingMode culling() { return culling; }
	public long frameGeneration() { return frameGeneration; }
}
