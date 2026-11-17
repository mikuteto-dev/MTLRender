package dev.mikuteto.mtlrender.pack.gbuffer;

import dev.mikuteto.mtlrender.pack.ids.IdMap;

import java.util.Objects;
import java.util.Optional;

public final class GbufferDrawState {
	public record Attribute(float x, float y, float z, float w) {
		public Attribute {
			if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(w)) {
				throw new IllegalArgumentException("gbuffer attribute must be finite");
			}
		}
	}

	private final int entityId;
	private final int blockEntityId;
	private final int renderStage;
	private final int currentRenderedItemId;
	private final Optional<Attribute> mcEntity;
	private final Optional<Attribute> midBlock;
	private final Optional<Attribute> tangent;

	public GbufferDrawState(int entityId, int blockEntityId, int renderStage, int currentRenderedItemId,
		Optional<Attribute> mcEntity, Optional<Attribute> midBlock, Optional<Attribute> tangent) {
		this.entityId = entityId;
		this.blockEntityId = blockEntityId;
		this.renderStage = renderStage;
		this.currentRenderedItemId = currentRenderedItemId;
		this.mcEntity = Objects.requireNonNull(mcEntity, "mcEntity");
		this.midBlock = Objects.requireNonNull(midBlock, "midBlock");
		this.tangent = Objects.requireNonNull(tangent, "tangent");
	}

	public static GbufferDrawState zero() {
		return new GbufferDrawState(0, 0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty());
	}

	public static GbufferDrawState fromRegistryIds(int entityRegistryId, int blockEntityId, GbufferStage stage,
		int itemRegistryId, IdMap idMap, Optional<Attribute> mcEntity, Optional<Attribute> midBlock,
		Optional<Attribute> tangent) {
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(idMap, "idMap");
		return new GbufferDrawState(mapped(idMap.entityId(entityRegistryId)), blockEntityId, stage.ordinal(),
			mapped(idMap.itemId(itemRegistryId)), mcEntity, midBlock, tangent);
	}

	private static int mapped(int value) {
		return value < 0 ? 0 : value;
	}

	public int entityId() { return entityId; }
	public int blockEntityId() { return blockEntityId; }
	public int renderStage() { return renderStage; }
	public int currentRenderedItemId() { return currentRenderedItemId; }
	public Optional<Attribute> mcEntity() { return mcEntity; }
	public Optional<Attribute> midBlock() { return midBlock; }
	public Optional<Attribute> tangent() { return tangent; }
}
