package dev.mikuteto.mtlrender.gui;

public record GuiFrameCacheKey(
	int windowWidth,
	int windowHeight,
	float guiScale,
	long resourceGeneration,
	long screenIdentityToken,
	long overlayIdentityToken,
	int mouseX,
	int mouseY,
	long hoverStateToken,
	long dynamicTimeToken,
	boolean hudHidden,
	float clearRed,
	float clearGreen,
	float clearBlue,
	float clearAlpha,
	long blurBoundaryToken,
	long panoramaStateToken,
	long contentFingerprint
) {
	public GuiFrameCacheKey(final long snapshotHash, final long contextHash, final long dynamicToken) {
		this(0, 0, 0.0F, contextHash, 0L, 0L, 0, 0, 0L, dynamicToken, false, 0.0F, 0.0F, 0.0F, 0.0F, 0L, 0L, snapshotHash);
	}

	public static GuiFrameCacheKey staticKey(final long snapshotHash, final long contextHash) {
		return new GuiFrameCacheKey(snapshotHash, contextHash, 0L);
	}

	public static GuiFrameCacheKey dynamicKey(final long snapshotHash, final long contextHash, final long token) {
		return new GuiFrameCacheKey(snapshotHash, contextHash, token);
	}

	public boolean isDynamic() {
		return this.dynamicTimeToken != 0L;
	}
}
