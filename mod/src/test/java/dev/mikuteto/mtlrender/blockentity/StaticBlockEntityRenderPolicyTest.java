package dev.mikuteto.mtlrender.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StaticBlockEntityRenderPolicyTest {
	@BeforeAll
	static void bootstrap() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}

	private static final long SECTION = 42L;
	private static final BlockPos POS = new BlockPos(4, 20, 8);

	@Test
	void unpublishedAndGenerationMismatchAreRetained() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		registry.install(staticPlan(7L));
		BlockEntityRenderState state = state();

		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAINED_DYNAMIC,
			StaticBlockEntityRenderPolicy.decide(registry, SECTION, state, false));

		StaticBlockEntityBakeRegistry newer = new StaticBlockEntityBakeRegistry(() -> true);
		newer.install(staticPlan(8L));
		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAINED_DYNAMIC,
			StaticBlockEntityRenderPolicy.decide(newer, SECTION, state, false));
	}

	@Test
	void onlyFullyPublishedStaticInstancesAreSkipped() {
		StaticBlockEntityBakeRegistry registry = registry(7L, staticPlan(7L));
		assertEquals(StaticBlockEntityRenderPolicy.Decision.SKIP_STATIC,
			StaticBlockEntityRenderPolicy.decide(registry, SECTION, state(), false));

		StaticBlockEntityBakeRegistry partial = registry(7L, partialPlan(7L));
		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAINED_DYNAMIC,
			StaticBlockEntityRenderPolicy.decide(partial, SECTION, state(), false));
	}

	@Test
	void globalBreakOutlineAndEmissivePathsRemainDynamic() {
		StaticBlockEntityBakeRegistry registry = registry(7L, staticPlan(7L));
		BlockEntityRenderState global = state();
		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAIN_EXCEPTION,
			StaticBlockEntityRenderPolicy.decide(registry, SECTION, global, true));

		BlockEntityRenderState breaking = state();
		breaking.breakProgress = new ModelFeatureRenderer.CrumblingOverlay(1, null);
		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAIN_EXCEPTION,
			StaticBlockEntityRenderPolicy.decide(registry, SECTION, breaking, false));
	}

	@Test
	void partialChestKeepsDynamicSubmissionForClosedAndOpenStates() {
		StaticBlockEntityBakeRegistry registry = registry(7L, partialPlan(7L));
		ChestRenderState closed = chestState();
		closed.open = 0.0F;
		assertTrue(StaticBlockEntityRenderPolicy.chestDynamicOnly(registry, SECTION, closed, false));
		ChestRenderState open = chestState();
		open.open = 1.0F;
		assertTrue(StaticBlockEntityRenderPolicy.chestDynamicOnly(registry, SECTION, open, false));
		ChestRenderState changing = chestState();
		changing.open = 0.37F;
		assertTrue(StaticBlockEntityRenderPolicy.chestDynamicOnly(registry, SECTION, changing, false));
	}

	@Test
	void unsupportedChestAndSignTextRemainOnVanillaPath() {
		StaticBlockEntityBakeRegistry registry = registry(7L, partialPlan(7L));
		ChestRenderState unsupported = chestState();
		unsupported.blockEntityType = BlockEntityTypes.TRAPPED_CHEST;
		assertFalse(StaticBlockEntityRenderPolicy.chestDynamicOnly(registry, SECTION, unsupported, false));

		StaticBlockEntityBakeKey signKey = new StaticBlockEntityBakeKey(7L, "minecraft:sign", "oak");
		StaticBlockEntityBakePlan signPlan = new StaticBlockEntityBakePlan(signKey, List.of(), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.TERRAIN_OWNED, "board-post"),
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, "text")));
		StaticBlockEntityBakeRegistry signRegistry = registry(7L, signPlan);
		SignRenderState sign = new SignRenderState();
		sign.blockEntityType = BlockEntityTypes.SIGN;
		sign.drawOutline = true;
		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAINED_DYNAMIC,
			StaticBlockEntityRenderPolicy.decide(signRegistry, SECTION, sign, false));
		sign.breakProgress = new ModelFeatureRenderer.CrumblingOverlay(1, null);
		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAIN_EXCEPTION,
			StaticBlockEntityRenderPolicy.decide(signRegistry, SECTION, sign, false));
	}

	@Test
	void unsupportedAndCustomInstancesAreRetained() {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		StaticBlockEntityBakeKey key = new StaticBlockEntityBakeKey(7L, "minecraft:chest", "default");
		StaticBlockEntityBakePlan plan = new StaticBlockEntityBakePlan(key, List.of(), List.of(
			new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.CUSTOM_RENDERER, "renderer")));
		registry.install(plan);
		StaticBlockEntityBakePublication publication = StaticBlockEntityBakePublication.candidate(SECTION, POS.asLong(), plan);
		registry.tryPublish(publication);
		assertEquals(StaticBlockEntityRenderPolicy.Decision.RETAINED_DYNAMIC,
			StaticBlockEntityRenderPolicy.decide(registry, SECTION, state(), false));
	}

	private static StaticBlockEntityBakeRegistry registry(long generation, StaticBlockEntityBakePlan plan) {
		StaticBlockEntityBakeRegistry registry = new StaticBlockEntityBakeRegistry(() -> true);
		registry.install(plan);
		registry.tryPublish(StaticBlockEntityBakePublication.candidate(SECTION, POS.asLong(), plan));
		return registry;
	}

	private static StaticBlockEntityBakePlan staticPlan(long generation) {
		return new StaticBlockEntityBakePlan(new StaticBlockEntityBakeKey(generation, "minecraft:chest", "default"),
			List.of(new StaticBlockEntityBakePart("body", new byte[] {1}, "chest")), List.of());
	}

	private static StaticBlockEntityBakePlan partialPlan(long generation) {
		return new StaticBlockEntityBakePlan(new StaticBlockEntityBakeKey(generation, "minecraft:chest", "default"),
			List.of(new StaticBlockEntityBakePart("body", new byte[] {1}, "chest")), List.of(
				new StaticBlockEntityBakeFallback(StaticBlockEntityBakeFallback.Reason.DYNAMIC_SUBPART_RETAINED, "lid")));
	}

	private static ChestRenderState chestState() {
		ChestRenderState state = new ChestRenderState();
		state.blockPos = POS;
		state.blockEntityType = BlockEntityTypes.CHEST;
		return state;
	}

	private static BlockEntityRenderState state() {
		BlockEntityRenderState state = new BlockEntityRenderState();
		state.blockPos = POS;
		state.blockEntityType = BlockEntityTypes.CHEST;
		return state;
	}
}
