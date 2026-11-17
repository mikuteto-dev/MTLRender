package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.client.renderer.blockentity.state.ShulkerBoxRenderState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StaticBlockEntityDynamicPolicyTest {
	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	@Test
	void publishedVariantSkipsOnlyTheBakedBase() {
		BlockPos pos = new BlockPos(2, 3, 4);
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(1, "minecraft:shulker_box", "purple");
		StaticBlockEntityBakePlan plan = new StaticBlockEntityBakePlan(key,
			List.of(new StaticBlockEntityBakePart("base", new byte[] {1}, "purple")),
			List.of(new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, "lid")));
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		registry.replace(1, List.of(plan));
		registry.publish(StaticBlockEntityBakePublication.candidate(pos.asLong(), pos.asLong(), plan));
		ShulkerBoxRenderState state = state(pos, net.minecraft.world.item.DyeColor.PURPLE);

		assertEquals(StaticBlockEntityDynamicPolicy.Decision.SKIP_STATIC,
			StaticBlockEntityDynamicPolicy.decide(registry, state, "base"));
		assertEquals(StaticBlockEntityDynamicPolicy.Decision.RETAIN_DYNAMIC,
			StaticBlockEntityDynamicPolicy.decide(registry, state, "lid"));
	}

	@Test
	void missingVariantAndBreakOverlayUseCompleteRenderer() {
		BlockPos pos = new BlockPos(2, 3, 4);
		ShulkerBoxRenderState state = state(pos, net.minecraft.world.item.DyeColor.RED);
		assertEquals(StaticBlockEntityDynamicPolicy.Decision.COMPLETE,
			StaticBlockEntityDynamicPolicy.decide(state, "base"));
		state.breakProgress = null;
		assertEquals(StaticBlockEntityDynamicPolicy.Decision.COMPLETE,
			StaticBlockEntityDynamicPolicy.decide(state, "lid"));
	}

	private static ShulkerBoxRenderState state(BlockPos pos, net.minecraft.world.item.DyeColor color) {
		ShulkerBoxRenderState state = new ShulkerBoxRenderState();
		state.blockPos = pos;
		state.blockEntityType = BlockEntityTypes.SHULKER_BOX;
		state.color = color;
		return state;
	}
}
