package dev.mikuteto.mtlrender.blockentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

import dev.mikuteto.mtlrender.config.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

public final class BlockEntityBakeHooks {
	public enum Kind {
		STATIC_PARTIAL,
		DYNAMIC_ONLY,
		FALLBACK
	}

	public record Decision(Kind kind, StaticBlockEntityBakePlan plan) {
	}

	private static final int CACHE_LIMIT = 256;
	private static final Map<ObservationKey, Decision> OBSERVATIONS = new LinkedHashMap<>(CACHE_LIMIT, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ObservationKey, Decision> entry) {
			return size() > CACHE_LIMIT;
		}
	};
	private static long cachedGeneration = -1L;

	private BlockEntityBakeHooks() {
	}

	public static Decision observeExtraction(BlockEntity blockEntity, BlockEntityRenderState state, Class<?> rendererClass) {
		String typeKey = typeKey(blockEntity);
		String variantKey = variantKey(blockEntity);
		Decision decision = decide(typeKey, variantKey, rendererClass);
		if (Config.get().blockEntities.resultCache) {
			remember(blockEntity.getBlockPos().asLong(), typeKey, variantKey, decision);
		}
		return decision;
	}

	public static Decision observeSubmission(BlockEntityRenderState state, Class<?> rendererClass) {
		String typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(state.blockEntityType).toString();
		if (!VanillaBlockEntityBakeCatalog.isVanillaRenderer(typeKey, rendererClass)) {
			return decide(typeKey, "default", rendererClass);
		}
		Decision cached = Config.get().blockEntities.resultCache
			? recall(state.blockPos.asLong(), typeKey)
			: null;
		return cached != null ? cached : decide(typeKey, "default", rendererClass);
	}

	public static Decision decide(String typeKey, String variantKey, Class<?> rendererClass) {
		StaticBlockEntityBakeRegistry registry = VanillaBlockEntityBakeSet.registry();
		OptionalLong generation = registry.generation();
		if (generation.isEmpty()) {
			return fallback(0L, typeKey, variantKey, StaticBlockEntityBakeFallback.Reason.RESOURCE_GENERATION_MISMATCH);
		}
		return decide(registry, generation.getAsLong(), typeKey, variantKey,
			rendererClass == null ? null : rendererClass.getName());
	}

	public static Decision decide(StaticBlockEntityBakeRegistry registry, long generation,
		String typeKey, String variantKey, String rendererClassName) {
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(generation, typeKey, variantKey);
		if (!VanillaBlockEntityBakeCatalog.isVanillaRenderer(typeKey, rendererClassName)) {
			return fallback(key, rendererClassName == null
				? StaticBlockEntityBakeFallback.Reason.UNSUPPORTED_RENDERER
				: StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER);
		}
		return registry.lookup(key).map(BlockEntityBakeHooks::classify).orElseGet(
			() -> fallback(key, StaticBlockEntityBakeFallback.Reason.RESOURCE_GENERATION_MISMATCH));
	}

	private static Decision classify(StaticBlockEntityBakePlan plan) {
		if (plan.hasStaticPortion()) {
			return new Decision(Kind.STATIC_PARTIAL, plan);
		}
		if (plan.fallbacks().stream().anyMatch(value -> switch (value.reason()) {
			case CUSTOM_RENDERER, UNSUPPORTED_RENDERER, RESOURCE_GENERATION_MISMATCH, MISSING_PART,
				HIDDEN_PART, SKIPPED_PART, ANIMATED_POSE, INVALID_GEOMETRY, UNSUPPORTED_MODEL, DISABLED -> true;
			default -> false;
		})) {
			return new Decision(Kind.FALLBACK, plan);
		}
		return new Decision(Kind.DYNAMIC_ONLY, plan);
	}

	private static Decision fallback(StaticBlockEntityBakeKey key, StaticBlockEntityBakeFallback.Reason reason) {
		return new Decision(Kind.FALLBACK, new StaticBlockEntityBakePlan(key, java.util.List.of(),
			java.util.List.of(new StaticBlockEntityBakeFallback(reason, ""))));
	}

	private static Decision fallback(long generation, String typeKey, String variantKey,
		StaticBlockEntityBakeFallback.Reason reason) {
		return fallback(new StaticBlockEntityBakeKey(generation, typeKey, variantKey), reason);
	}

	public static String typeKey(BlockEntity blockEntity) {
		return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString();
	}

	public static String variantKey(BlockEntity blockEntity) {
		if (blockEntity instanceof ShulkerBoxBlockEntity shulker) {
			return shulker.getColor() == null ? "default" : shulker.getColor().getName();
		}
		if (blockEntity.getBlockState().getBlock() instanceof ChestBlock) {
			String material = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).getPath();
			material = switch (material) {
				case "chest" -> "oak";
				default -> material;
			};
			ChestType type = blockEntity.getBlockState().getValue(ChestBlock.TYPE);
			String shape = type == ChestType.SINGLE ? "single" : type == ChestType.LEFT ? "left" : "right";
			return material + ":" + shape;
		}
		return "default";
	}

	private static synchronized void remember(long position, String typeKey, String variantKey, Decision decision) {
		long generation = decision.plan().key().resourceGeneration();
		if (cachedGeneration != generation) {
			OBSERVATIONS.clear();
			cachedGeneration = generation;
		}
		OBSERVATIONS.put(new ObservationKey(position, typeKey), decision);
	}

	private static synchronized Decision recall(long position, String typeKey) {
		return OBSERVATIONS.get(new ObservationKey(position, typeKey));
	}

	private record ObservationKey(long position, String typeKey) {
	}
}
