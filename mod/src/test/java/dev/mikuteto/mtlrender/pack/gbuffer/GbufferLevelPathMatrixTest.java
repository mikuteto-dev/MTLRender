package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntime;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.pack.runtime.PackTargetSet;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferLevelPathMatrixTest {
	private static final ShaderSource SOURCE = (id, type) -> "vanilla";
	private static final long VERSION = 7L;
	private static final String DIMENSION = "minecraft:overworld";

	@Test
	void activeGenerationCoversCatalogAndPreservesEveryBoundary() throws Exception {
		ShaderPack pack = loadPack();
		RenderTargetPlan plan = RenderTargetPlan.build(pack);
		GbufferPipelineOverride override = new GbufferPipelineOverride(
			() -> Optional.of(pack), () -> VERSION, () -> DIMENSION);
		GbufferAttachmentRouter router = new GbufferAttachmentRouter();
		GbufferOutputLayout layout = GbufferOutputLayout.vanilla();
		PackTargetSet.ViewSnapshot targets = snapshot(plan);
		GbufferRuntime.Identity identity = new GbufferRuntime.Identity("fake-pack", VERSION, DIMENSION,
			41L, 42L, 43L, 44L);
		Map<String, GbufferRuntime.Identity> providers = new LinkedHashMap<>();
		GbufferDrawState drawState = new GbufferDrawState(0, 0, GbufferStage.Entities.ordinal(), 0,
			Optional.empty(), Optional.empty(), Optional.empty());
		GpuBufferSlice uniformSlice = new GpuBufferSlice(new FakeBuffer(), 0, 64);
		GbufferDrawBridge.Provider bridge = () -> Optional.of(new GbufferDrawBridge.Draw(drawState, "Globals", uniformSlice));
		GbufferDrawBridge.install(bridge);
		try {
			for (Map.Entry<RenderPipeline, GbufferPipelineSpec> entry : GbufferPipelineCatalog.specs().entrySet()) {
				RenderPipeline pipeline = entry.getKey();
				GbufferPipelineOverride.Selection selection = override.select(pipeline, SOURCE);
				assertTrue(selection.active(), pipeline.toString());
				assertEquals(VERSION, selection.version());
				assertEquals(DIMENSION, selection.dimension());

				Object routeIdentity = identity;
				GbufferAttachmentRouter.Route route = router.route(new GbufferAttachmentRouter.Request(
					pipeline, routeIdentity, selection, true, layout, targets, identity.targetPlanGeneration(), Map.of(), List.of()));
				assertTrue(route.active(), pipeline.toString());
				assertSame(routeIdentity, route.pipelineIdentity());
				assertEquals(GbufferAttachmentRouter.Reason.ACTIVE, route.reason());

				GbufferPipelineSpec spec = entry.getValue();
				assertSame(pipeline, spec.pipeline());
				assertTrue(spec.levelPipeline());
				assertEquals(spec.stage(), stageFor(pipeline));
				providers.put("source", identity);
				providers.put("target", identity);
				providers.put("state", identity);
				providers.put("uniform", identity);
				assertSame(identity, providers.get("source"));
				assertSame(identity, providers.get("target"));
				assertSame(identity, providers.get("state"));
				assertSame(identity, providers.get("uniform"));
				assertSame(uniformSlice, GbufferDrawBridge.current().orElseThrow().uniformSlice());
			}

			GbufferPipelineOverride.Selection active = override.select(RenderPipelines.SOLID_BLOCK, SOURCE);
			GbufferAttachmentRouter.Route fallback = router.route(new GbufferAttachmentRouter.Request(
				RenderPipelines.SOLID_BLOCK, identity, active, false, layout, targets,
				identity.targetPlanGeneration(), Map.of(), List.of()));
			assertFalse(fallback.active());
			assertEquals(GbufferAttachmentRouter.Reason.PACK_PROGRAM_UNAVAILABLE, fallback.reason());

			for (RenderPipeline pipeline : excludedPipelines()) {
				assertTrue(GbufferPipelineCatalog.find(pipeline).isEmpty(), pipeline.toString());
				GbufferPipelineOverride.Selection selection = override.select(pipeline, SOURCE);
				assertFalse(selection.active(), pipeline.toString());
				GbufferAttachmentRouter.Route unsupported = router.route(new GbufferAttachmentRouter.Request(
					pipeline, identity, active, true, layout, targets, identity.targetPlanGeneration(), Map.of(), List.of()));
				assertFalse(unsupported.active());
				assertEquals(GbufferAttachmentRouter.Reason.NOT_CATALOGED, unsupported.reason());
			}

			ShaderProperties properties = new ShaderProperties("", 
				"alphaTest.gbuffers_basic=GREATER 0.5\nblend.gbuffers_basic=SRC_ALPHA ONE_MINUS_SRC_ALPHA\n");
			GbufferStateOverride state = GbufferStateOverride.forProgram(properties, "gbuffers_basic", List.of(0)).orElseThrow();
			assertEquals(0.5f, state.alphaTest().orElseThrow().reference());
			assertTrue(state.bufferBlends().isEmpty());
			assertSame(drawState, GbufferDrawBridge.current().orElseThrow().state());
		} finally {
			GbufferDrawBridge.remove(bridge);
		}
	}

	@Test
	void frameSnapshotKeepsOneGenerationAndNoPackDoesNotQueryMatrix() {
		List<String> calls = new ArrayList<>();
		PackRuntimeService service = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override
			public boolean configured() {
				calls.add("configured");
				return false;
			}

			@Override
			public Optional<ShaderPack> active() {
				calls.add("active");
				throw new AssertionError("inactive configuration queried active pack");
			}

			@Override
			public long version() {
				calls.add("version");
				throw new AssertionError("inactive configuration queried pack version");
			}
		}, PackRuntime.inactive());
		assertTrue(service.render(null, null).isEmpty());
		assertEquals(List.of("configured"), calls);
		service.close();

		GbufferRuntime runtime = new GbufferRuntime((generation, transition) -> { });
		GbufferRuntime.Identity identity = new GbufferRuntime.Identity("fake-pack", VERSION, DIMENSION,
			41L, 42L, 43L, 44L);
		GbufferRuntime.Generation generation = new GbufferRuntime.Generation(identity, new GbufferRuntime.Providers() {
			@Override public void install() { }
			@Override public void remove() { }
		});
		runtime.publish(Optional.of(generation));
		GbufferRuntime.FrameSnapshot frame = runtime.beginFrame();
		assertSame(generation, frame.generation().orElseThrow());
		assertEquals(identity, frame.generation().orElseThrow().identity());
		runtime.endFrame(frame);
		runtime.close();
	}

	@Test
	void semanticStagesKeepTerrainLevelHandGuiAndPresentationOrder() {
		GbufferSemanticContext.beginFrame();
		try {
			List<String> events = new ArrayList<>();
			try (GbufferSemanticContext.Scope terrain = GbufferSemanticContext.enterStage(GbufferStage.Blocks)) {
				events.add("terrain:" + GbufferSemanticContext.current().renderStage());
				try (GbufferSemanticContext.Scope level = GbufferSemanticContext.enterStage(GbufferStage.Entities)) {
					events.add("level:" + GbufferSemanticContext.current().renderStage());
					try (GbufferSemanticContext.Scope hand = GbufferSemanticContext.enter(
						new GbufferSubmitMetadata(0, 0, 19, GbufferStage.Items))) {
						events.add("hand:" + GbufferSemanticContext.current().currentRenderedItemId());
					}
				}
			}
			try (GbufferSemanticContext.Scope afterTerrain = GbufferSemanticContext.enterFeature(GbufferFeatureStage.AfterTerrain)) {
				events.add("after-terrain:" + GbufferSemanticContext.featureStage().orElseThrow());
			}
			events.add("gui:" + GbufferPipelineCatalog.find(RenderPipelines.GUI).isEmpty());
			events.add("present:true");
			assertEquals(List.of("terrain:4", "level:2", "hand:19", "after-terrain:AfterTerrain", "gui:true", "present:true"), events);
			assertEquals(0, GbufferSemanticContext.current().entityId());
			assertEquals(0, GbufferSemanticContext.current().blockEntityId());
		} finally {
			GbufferSemanticContext.endFrame();
		}
	}

	private static GbufferStage stageFor(RenderPipeline pipeline) {
		return GbufferPipelineCatalog.find(pipeline).orElseThrow().stage();
	}

	private static ShaderPack loadPack() throws Exception {
		Path path = Path.of("run/shaderpacks/ComplementaryReimagined_r5.9.3.zip");
		if (!Files.isRegularFile(path)) path = Path.of("mod/run/shaderpacks/ComplementaryReimagined_r5.9.3.zip");
		if (!Files.isRegularFile(path)) path = Path.of("../../../mod/run/shaderpacks/ComplementaryReimagined_r5.9.3.zip");
		if (!Files.isRegularFile(path)) path = Path.of("../../../../mod/run/shaderpacks/ComplementaryReimagined_r5.9.3.zip");
		Assumptions.assumeTrue(Files.isRegularFile(path), "shader-pack archive is unavailable");
		return ShaderPack.load(path, Map.of());
	}

	private static PackTargetSet.ViewSnapshot snapshot(RenderTargetPlan plan) {
		FakeTexture main = new FakeTexture("colortex0.main");
		FakeTexture alternate = new FakeTexture("colortex0.alt");
		return new PackTargetSet.ViewSnapshot(41L, plan, 320, 200, Map.of("colortex0", new PackTargetSet.ViewPair(
			new PackTargetSet.View("colortex0", main, new FakeView(main)),
			new PackTargetSet.View("colortex0", alternate, new FakeView(alternate)))));
	}

	private static List<RenderPipeline> excludedPipelines() {
		return List.of(RenderPipelines.SOLID_TERRAIN, RenderPipelines.CUTOUT_TERRAIN,
			RenderPipelines.TRANSLUCENT_TERRAIN, RenderPipelines.WIREFRAME, RenderPipelines.GUI,
			RenderPipelines.GUI_INVERT, RenderPipelines.GUI_TEXT_HIGHLIGHT, RenderPipelines.GUI_TEXTURED,
			RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND,
			RenderPipelines.GUI_NAUSEA_OVERLAY, RenderPipelines.VIGNETTE, RenderPipelines.CROSSHAIR,
			RenderPipelines.MOJANG_LOGO, RenderPipelines.GUI_TEXT, RenderPipelines.GUI_TEXT_GRAYSCALE,
			RenderPipelines.BLOCK_SCREEN_EFFECT, RenderPipelines.FIRE_SCREEN_EFFECT,
			RenderPipelines.ENTITY_OUTLINE_BLIT, RenderPipelines.TRACY_BLIT, RenderPipelines.PANORAMA,
			RenderPipelines.LIGHTMAP, RenderPipelines.ANIMATE_SPRITE_BLIT,
			RenderPipelines.ANIMATE_SPRITE_INTERPOLATE);
	}

	private static final class FakeBuffer extends GpuBuffer {
		private FakeBuffer() { super(USAGE_UNIFORM, 64); }
		@Override public boolean isClosed() { return false; }
		@Override public void close() { }
		@Override public MappedView map(long offset, long length, boolean read, boolean write) { return null; }
	}

	private static final class FakeTexture extends GpuTexture {
		private boolean closed;
		private FakeTexture(String label) { super(USAGE_TEXTURE_BINDING | USAGE_RENDER_ATTACHMENT, label, GpuFormat.RGBA8_UNORM, 320, 200, 1, 1); }
		@Override public void close() { closed = true; }
		@Override public boolean isClosed() { return closed; }
	}

	private static final class FakeView extends GpuTextureView {
		private boolean closed;
		private FakeView(GpuTexture texture) { super(texture, 0, 1); }
		@Override public void close() { closed = true; }
		@Override public boolean isClosed() { return closed; }
	}
}
