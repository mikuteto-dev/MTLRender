package dev.mikuteto.mtlrender.blockentity;

import java.util.Optional;
import dev.mikuteto.mtlrender.debug.StaticBlockEntityBakeStats;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.core.registries.BuiltInRegistries;

public final class StaticBlockEntityRenderPolicy {
	private StaticBlockEntityRenderPolicy() {
	}

	public static Decision decide(StaticBlockEntityBakeRegistry registry, long sectionKey,
		BlockEntityRenderState state, boolean globallyRendered) {
		if (globallyRendered || state.breakProgress != null) {
			return Decision.RETAIN_EXCEPTION;
		}
		String typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(state.blockEntityType).toString();
		Optional<StaticBlockEntityBakePublication.StaticBlockEntityBakeInstance> instance =
			registry.publishedByPositionAndType(sectionKey, state.blockPos.asLong(), typeKey);
		if (instance.isPresent() && instance.get().fullyStaticAndSuppressible()) {
			return Decision.SKIP_STATIC;
		}
		return Decision.RETAINED_DYNAMIC;
	}

	public static Decision decide(long sectionKey, BlockEntityRenderState state, boolean globallyRendered) {
		return decide(VanillaBlockEntityBakeSet.registry(), sectionKey, state, globallyRendered);
	}

	public static boolean suppress(long sectionKey, BlockEntityRenderState state, boolean globallyRendered) {
		Decision decision = decide(sectionKey, state, globallyRendered);
		count(decision);
		return decision == Decision.SKIP_STATIC;
	}

	public static boolean chestDynamicOnly(long sectionKey, ChestRenderState state, boolean globallyRendered) {
		return chestDynamicOnly(VanillaBlockEntityBakeSet.registry(), sectionKey, state, globallyRendered);
	}

	public static boolean chestDynamicOnly(StaticBlockEntityBakeRegistry registry, long sectionKey,
		ChestRenderState state, boolean globallyRendered) {
		if (globallyRendered || state.breakProgress != null) {
			return false;
		}
		String typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(state.blockEntityType).toString();
		if (!typeKey.equals("minecraft:chest") && !typeKey.equals("minecraft:trapped_chest")
			&& !typeKey.equals("minecraft:ender_chest")) {
			return false;
		}
		return registry.publishedByPositionAndType(sectionKey, state.blockPos.asLong(), typeKey)
			.map(instance -> instance.hasStaticPart() && instance.hasDynamicPart())
			.orElse(false);
	}

	public static Counters counters() {
		StaticBlockEntityBakeStats.Snapshot snapshot = StaticBlockEntityBakeStats.snapshot(false);
		return new Counters(snapshot.skippedStatic(), snapshot.retainedDynamic(), snapshot.retainedException());
	}

	public static void resetCounters() {
		StaticBlockEntityBakeStats.reset();
	}

	private static void count(Decision decision) {
		switch (decision) {
			case SKIP_STATIC -> StaticBlockEntityBakeStats.skippedStatic();
			case RETAINED_DYNAMIC -> StaticBlockEntityBakeStats.retainedDynamic();
			case RETAIN_EXCEPTION -> StaticBlockEntityBakeStats.retainedException();
		}
	}

	public enum Decision {
		SKIP_STATIC,
		RETAINED_DYNAMIC,
		RETAIN_EXCEPTION
	}

	public record Counters(long skippedStatic, long retainedDynamic, long retainedException) {
	}
}
