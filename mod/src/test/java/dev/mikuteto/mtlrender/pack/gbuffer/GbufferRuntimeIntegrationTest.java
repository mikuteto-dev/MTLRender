package dev.mikuteto.mtlrender.pack.gbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.runtime.PackPassExecutor;
import dev.mikuteto.mtlrender.pack.runtime.PackPassFailurePolicy;
import dev.mikuteto.mtlrender.pack.runtime.PackProgramCache;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntime;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.pack.runtime.PackTargetSet;
import dev.mikuteto.mtlrender.pack.runtime.PackUniformState;
import dev.mikuteto.mtlrender.pack.runtime.RuntimeFrame;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class GbufferRuntimeIntegrationTest {
	@Test
	void fakeRuntimeCoversSwitchingCleanupBoundariesAndCatalog() throws Exception {
		List<String> noPackCalls = new ArrayList<>();
		PackRuntimeService noPack = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override
			public boolean configured() {
				noPackCalls.add("configured");
				return false;
			}

			@Override
			public Optional<ShaderPack> active() {
				noPackCalls.add("active");
				throw new AssertionError("no-pack path read the active pack");
			}

			@Override
			public long version() {
				noPackCalls.add("version");
				throw new AssertionError("no-pack path read the pack version");
			}
		}, PackRuntime.inactive());
		assertTrue(noPack.render(null, null).isEmpty());
		assertEquals(List.of("configured"), noPackCalls);
		noPack.close();

		List<String> events = new ArrayList<>();
		GbufferRuntime gbuffer = new GbufferRuntime((generation, transition) ->
			events.add("retire:" + generation.identity().packVersion() + ":" + transition));
		GbufferRuntime.Generation first = generation(1L, "overworld", events);
		GbufferRuntime.Generation reloaded = generation(2L, "overworld", events);
		GbufferRuntime.Generation dimension = generation(2L, "the_nether", events);
		GbufferRuntime.Generation resized = generation(2L, "the_nether", events);

		gbuffer.publish(GbufferRuntime.Transition.PACK_RELOAD, Optional.of(first));
		GbufferRuntime.FrameSnapshot oldFrame = gbuffer.beginFrame();
		gbuffer.publish(GbufferRuntime.Transition.PACK_RELOAD, Optional.of(reloaded));
		assertIdentity(oldFrame.generation().orElseThrow(), 1L, "overworld");
		gbuffer.endFrame(oldFrame);
		gbuffer.dimensionChange(Optional.of(dimension));
		gbuffer.resize(Optional.of(resized));
		gbuffer.disablePack();
		gbuffer.publish(GbufferRuntime.Transition.PACK_RELOAD, Optional.of(first));
		gbuffer.worldUnload();
		gbuffer.publish(GbufferRuntime.Transition.PACK_RELOAD, Optional.of(reloaded));
		gbuffer.close();
		gbuffer.close();
		assertEquals(List.of(
			"install:1:overworld", "remove:1:overworld", "retire:1:PACK_RELOAD", "install:2:overworld",
			"remove:2:overworld", "retire:2:DIMENSION_CHANGE", "install:2:the_nether",
			"remove:2:the_nether", "retire:2:RESIZE", "install:2:the_nether",
			"remove:2:the_nether", "retire:2:PACK_DISABLE", "install:1:overworld",
			"remove:1:overworld", "retire:1:WORLD_UNLOAD", "install:2:overworld",
			"remove:2:overworld", "retire:2:BACKEND_CLOSE"), events);

		ShaderPack pack = loadPack();
		GbufferPipelineOverride override = new GbufferPipelineOverride(
			() -> Optional.of(pack), () -> 7L, () -> pack.baseFolder());
		for (RenderPipeline pipeline : GbufferPipelineCatalog.levelPipelines()) {
			GbufferPipelineOverride.Selection selection = override.select(pipeline, (id, type) -> "vanilla");
			assertTrue(selection.active(), pipeline.toString());
			assertEquals(7L, selection.version());
		}
		for (RenderPipeline pipeline : excludedPipelines()) {
			assertFalse(GbufferPipelineCatalog.find(pipeline).isPresent(), pipeline.toString());
			assertFalse(override.select(pipeline, (id, type) -> "vanilla").active(), pipeline.toString());
		}

		assertEquals(List.of("colortex0", "depthtex0", "depthtex1", "depthtex2"),
			PackTargetSet.seedCommands(true, true));
		PackUniformState uniforms = PackUniformState.from(pack);
		RuntimeFrame frame = RuntimeFrame.fromTargetPlan(320, 200,
			new RuntimeFrame.Viewport(0, 0, 320, 200), new RuntimeFrame.Scale(1.0f, 1.0f), pack.targetPlan());
		assertEquals(200.0, uniforms.forPass(frame, dev.mikuteto.mtlrender.pack.runtime.PassKind.FINAL)
			.value("viewHeight").number(0));
		PackProgramCache<String> cache = new PackProgramCache<>(
			() -> new PackProgramCache.State(7L, true), value -> events.add("pipeline-close:" + value));
		cache.pipeline("overworld", "final", (dimensionName, program) -> "old-pipeline");
		cache.close();
		assertTrue(events.contains("pipeline-close:old-pipeline"));
		new PackPassExecutor(new PackPassFailurePolicy());
		assertTrue(GbufferStateOverride.forProgram(ShaderProperties.empty(), "gbuffers_basic", List.of()).isEmpty());
		assertTrue(GbufferAttachmentRouter.vanilla().reason() == GbufferAttachmentRouter.Reason.VANILLA);

		String source = Files.readString(Path.of("src/main/java/dev/mikuteto/mtlrender/mixin/GameRendererMixin.java"));
		int levelRender = source.indexOf("LevelRenderer;render(");
		int serviceRender = source.indexOf("service.render(");
		assertTrue(levelRender >= 0 && levelRender < serviceRender);
		assertTrue(source.contains("shift = At.Shift.AFTER"));
		assertTrue(source.contains("ProjectionMatrixBuffer;getBuffer"));
		assertTrue(source.contains("MetalFx.nativeGuiTarget"));
	}

	private static GbufferRuntime.Generation generation(long version, String dimension, List<String> events) {
		GbufferRuntime.Identity identity = new GbufferRuntime.Identity("fake-pack", version, dimension,
			version * 10, version * 10 + 1, version * 10 + 2, version * 10 + 3);
		GbufferRuntime.Providers providers = new GbufferRuntime.Providers() {
			@Override
			public void install() {
				events.add("install:" + version + ":" + dimension);
			}

			@Override
			public void remove() {
				events.add("remove:" + version + ":" + dimension);
			}
		};
		return new GbufferRuntime.Generation(identity, providers);
	}

	private static void assertIdentity(GbufferRuntime.Generation generation, long version, String dimension) {
		GbufferRuntime.Identity identity = generation.identity();
		assertEquals(version, identity.packVersion());
		assertEquals(dimension, identity.dimension());
		assertEquals(version * 10, identity.targetPlanGeneration());
		assertEquals(version * 10 + 1, identity.pipelineSourceGeneration());
		assertEquals(version * 10 + 2, identity.alphaBlendGeneration());
		assertEquals(version * 10 + 3, identity.drawUniformGeneration());
	}

	private static ShaderPack loadPack() throws Exception {
		Path path = Path.of("run/shaderpacks/ComplementaryReimagined_r5.9.3.zip");
		Assumptions.assumeTrue(Files.isRegularFile(path), "shader-pack archive is unavailable");
		return ShaderPack.load(path, java.util.Map.of());
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
}
