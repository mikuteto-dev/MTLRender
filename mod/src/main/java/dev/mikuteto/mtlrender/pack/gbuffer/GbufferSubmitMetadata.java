package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.Objects;

public record GbufferSubmitMetadata(int entityId, int blockEntityId, int currentRenderedItemId, GbufferStage stage) {
	public GbufferSubmitMetadata {
		stage = Objects.requireNonNull(stage, "stage");
		entityId = knownId(entityId);
		blockEntityId = knownId(blockEntityId);
		currentRenderedItemId = knownId(currentRenderedItemId);
	}

	public static GbufferSubmitMetadata stage(GbufferStage stage) {
		return new GbufferSubmitMetadata(0, 0, 0, stage);
	}

	private static int knownId(int value) {
		return value < 0 ? 0 : value;
	}
}
