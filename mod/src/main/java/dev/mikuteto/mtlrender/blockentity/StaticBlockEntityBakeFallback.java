package dev.mikuteto.mtlrender.blockentity;

import java.util.Objects;

public record StaticBlockEntityBakeFallback(Reason reason, String partKey) {
	public StaticBlockEntityBakeFallback {
		reason = Objects.requireNonNull(reason, "reason");
		partKey = partKey == null || partKey.isBlank() ? "" : partKey;
	}

	public enum Reason {
		TERRAIN_OWNED,
		UNSUPPORTED_RENDERER,
		CUSTOM_RENDERER,
		DYNAMIC_ONLY_RENDERER,
		DYNAMIC_SUBPART_RETAINED,
		RESOURCE_GENERATION_MISMATCH,
		MISSING_PART,
		HIDDEN_PART,
		SKIPPED_PART,
		ANIMATED_POSE,
		INVALID_GEOMETRY,
		UNSUPPORTED_MODEL,
		DISABLED
	}
}
