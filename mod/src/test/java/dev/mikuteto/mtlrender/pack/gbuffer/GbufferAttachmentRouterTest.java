package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbufferAttachmentRouterTest {
	private static final ShaderSource VANILLA = (id, type) -> "";

	@Test
	void vanillaSelectionIsAStableNoOp() {
		GbufferAttachmentRouter.Request request = request(RenderPipelines.SOLID_BLOCK,
			new GbufferPipelineOverride.Selection(VANILLA, false, Long.MIN_VALUE, ""), false);

		GbufferAttachmentRouter.Route route = new GbufferAttachmentRouter().route(request);

		assertFalse(route.active());
		assertFalse(route.rejected());
		assertEquals(GbufferAttachmentRouter.Reason.VANILLA, route.reason());
		assertTrue(route.slots().isEmpty());
	}

	@Test
	void nonCatalogedPipelineCannotActivate() {
		RenderPipeline pipeline = RenderPipelines.GUI;
		GbufferAttachmentRouter.Request request = request(pipeline,
			new GbufferPipelineOverride.Selection(VANILLA, true, 4L, "minecraft:overworld"), true);

		GbufferAttachmentRouter.Route route = new GbufferAttachmentRouter().route(request);

		assertEquals(GbufferAttachmentRouter.Reason.NOT_CATALOGED, route.reason());
		assertFalse(route.active());
	}

	@Test
	void sampledNamesUseLegacyAliases() {
		GbufferAttachmentRouter.Sampled sampled = new GbufferAttachmentRouter.Sampled("gaux4", true);

		assertEquals("colortex7", sampled.name());
	}

	private static GbufferAttachmentRouter.Request request(RenderPipeline pipeline,
		GbufferPipelineOverride.Selection selection, boolean validProgram) {
		return new GbufferAttachmentRouter.Request(pipeline, new Object(), selection, validProgram,
			GbufferOutputLayout.vanilla(), null, 0L, null, null);
	}
}
