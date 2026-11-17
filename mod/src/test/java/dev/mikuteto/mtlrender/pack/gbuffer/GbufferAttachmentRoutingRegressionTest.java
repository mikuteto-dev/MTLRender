package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import dev.mikuteto.mtlrender.pack.runtime.PackTargetSet;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbufferAttachmentRoutingRegressionTest {
	private static final ShaderSource SOURCE = (id, type) -> "";
	private static final GbufferPipelineOverride.Selection ACTIVE =
		new GbufferPipelineOverride.Selection(SOURCE, true, 7L, "minecraft:overworld");
	private static final RenderTargetPlan PLAN = loadPlan();

	@Test
	void oneOutputRoutesItsRequestedColorSlot() {
		GbufferAttachmentRouter.Route route = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "4"), snapshot(7L, "colortex4"), 7L, Map.of(), List.of());

		assertTrue(route.active());
		assertEquals(1, route.slots().size());
		assertEquals(0, route.slots().get(0).location());
		assertEquals("colortex4", route.slots().get(0).targetName().orElseThrow());
	}

	@Test
	void sparseOutputsPreserveUnusedAttachmentSlots() {
		GbufferAttachmentRouter.Route route = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "4-1"),
			snapshot(7L, "colortex4", "colortex1"), 7L, Map.of(), List.of());

		assertEquals(3, route.slots().size());
		assertTrue(route.slots().get(1).targetName().isEmpty());
		assertEquals("colortex1", route.slots().get(2).targetName().orElseThrow());
	}

	@Test
	void legacyNamesResolveToCanonicalTargets() {
		assertEquals("colortex0", GbufferOutputLayout.canonicalTarget("gcolor").orElseThrow());
		GbufferAttachmentRouter.Sampled sampled = new GbufferAttachmentRouter.Sampled("gaux4", false);
		GbufferAttachmentRouter.Route route = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "7"),
			snapshot(7L, "colortex7"), 7L, Map.of(), List.of());

		assertEquals("colortex7", route.slots().get(0).targetName().orElseThrow());
		assertEquals("colortex7", sampled.name());
	}

	@Test
	void alternatePingPongTargetIsSelectedAndReadWriteAliasIsRejected() {
		GbufferAttachmentRouter.Route safe = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "0"),
			snapshot(7L, "colortex0"), 7L, Map.of("colortex0", true), List.of(new GbufferAttachmentRouter.Sampled("colortex0", false)));
		GbufferAttachmentRouter.Route conflict = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "0"),
			snapshot(7L, "colortex0"), 7L, Map.of(), List.of(new GbufferAttachmentRouter.Sampled("colortex0", false)));

		assertTrue(safe.active());
		assertTrue(safe.slots().get(0).alternate());
		assertFalse(conflict.active());
		assertEquals(GbufferAttachmentRouter.Reason.READ_WRITE_ALIAS, conflict.reason());
	}

	@Test
	void missingOptionalTargetAndFailedProgramFallBackWithoutRouting() {
		GbufferAttachmentRouter.Route missing = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "7"),
			snapshot(7L, "colortex0"), 7L, Map.of(), List.of());
		GbufferAttachmentRouter.Route failed = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "0"),
			snapshot(7L, "colortex0"), 7L, Map.of(), List.of(), false);

		assertTrue(missing.rejected());
		assertEquals(GbufferAttachmentRouter.Reason.MISSING_TARGET, missing.reason());
		assertFalse(failed.active());
		assertFalse(failed.rejected());
		assertEquals(GbufferAttachmentRouter.Reason.PACK_PROGRAM_UNAVAILABLE, failed.reason());
	}

	@Test
	void staleGenerationCannotResolveNewViews() {
		GbufferAttachmentRouter.Route stale = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "0"),
			snapshot(8L, "colortex0"), 7L, Map.of(), List.of());

		assertTrue(stale.rejected());
		assertEquals(GbufferAttachmentRouter.Reason.STALE_TARGET_SNAPSHOT, stale.reason());
	}

	@Test
	void routeCapturesOldGenerationAcrossReplacementAndCloseIsIdempotent() {
		PackTargetSet.ViewSnapshot oldSnapshot = snapshot(7L, "colortex0");
		PackTargetSet.View oldView = oldSnapshot.color("colortex0").orElseThrow().main();
		PackTargetSet.View replacement = snapshot(8L, "colortex0").color("colortex0").orElseThrow().main();
		GbufferAttachmentRouter.Route oldRoute = route(RenderPipelines.SOLID_BLOCK, layout("DRAWBUFFERS", "0"),
			oldSnapshot, 7L, Map.of(), List.of());

		assertSame(oldView.view(), oldRoute.slots().get(0).view().orElseThrow());
		assertFalse(oldView.view().isClosed());
		assertFalse(replacement.view().isClosed());
		oldView.view().close();
		oldView.view().close();
		assertTrue(oldView.view().isClosed());
		assertFalse(replacement.view().isClosed());
	}

	@Test
	void vanillaAndTerrainPathsAreNoOps() {
		GbufferAttachmentRouter router = new GbufferAttachmentRouter();
		GbufferAttachmentRouter.Request vanilla = new GbufferAttachmentRouter.Request(RenderPipelines.SOLID_BLOCK,
			new Object(), new GbufferPipelineOverride.Selection(SOURCE, false, Long.MIN_VALUE, ""), true,
			GbufferOutputLayout.vanilla(), null, 0L, null, null);
		GbufferAttachmentRouter.Request terrain = new GbufferAttachmentRouter.Request(RenderPipelines.SOLID_TERRAIN,
			new Object(), ACTIVE, true, layout("DRAWBUFFERS", "0"), snapshot(7L, "colortex0"), 7L, Map.of(), List.of());

		assertEquals(GbufferAttachmentRouter.Reason.VANILLA, router.route(vanilla).reason());
		assertEquals(GbufferAttachmentRouter.Reason.NOT_CATALOGED, router.route(terrain).reason());
		assertTrue(router.route(vanilla).slots().isEmpty());
	}

	@Test
	void catalogAndOverrideGatePackIdentity() {
		assertTrue(GbufferPipelineCatalog.find(RenderPipelines.SOLID_BLOCK).isPresent());
		assertTrue(GbufferPipelineCatalog.find(RenderPipelines.SOLID_TERRAIN).isEmpty());
		GbufferPipelineOverride override = new GbufferPipelineOverride(Optional::empty, () -> {
			throw new AssertionError("inactive pack must not poll version");
		}, () -> {
			throw new AssertionError("inactive pack must not poll dimension");
		});
		assertFalse(override.select(RenderPipelines.SOLID_BLOCK, SOURCE).active());
	}

	private static GbufferAttachmentRouter.Route route(RenderPipeline pipeline, GbufferOutputLayout layout,
		PackTargetSet.ViewSnapshot snapshot, long generation, Map<String, Boolean> alternate, List<GbufferAttachmentRouter.Sampled> sampled) {
		return route(pipeline, layout, snapshot, generation, alternate, sampled, true);
	}

	private static GbufferAttachmentRouter.Route route(RenderPipeline pipeline, GbufferOutputLayout layout,
		PackTargetSet.ViewSnapshot snapshot, long generation, Map<String, Boolean> alternate,
		List<GbufferAttachmentRouter.Sampled> sampled, boolean valid) {
		return new GbufferAttachmentRouter().route(new GbufferAttachmentRouter.Request(pipeline, new Object(), ACTIVE,
			valid, layout, snapshot, generation, alternate, sampled));
	}

	private static GbufferOutputLayout layout(String kind, String value) {
		return GbufferOutputLayout.from(GbufferOutputDirective.parse(kind, value), null, PLAN);
	}

	private static PackTargetSet.ViewSnapshot snapshot(long generation, String... names) {
		Map<String, PackTargetSet.ViewPair> colors = new LinkedHashMap<>();
		for (String name : names) {
			FakeTexture mainTexture = new FakeTexture(name + ".main");
			FakeTexture altTexture = new FakeTexture(name + ".alt");
			colors.put(name, new PackTargetSet.ViewPair(
				new PackTargetSet.View(name, mainTexture, new FakeView(mainTexture)),
				new PackTargetSet.View(name, altTexture, new FakeView(altTexture))));
		}
		return new PackTargetSet.ViewSnapshot(generation, PLAN, 1280, 720, colors);
	}

	private static RenderTargetPlan loadPlan() {
		try {
			Path root = Path.of(System.getProperty("user.dir"), "run", "shaderpacks");
			if (!Files.isDirectory(root)) root = Path.of(System.getProperty("user.dir"), "mod", "run", "shaderpacks");
			try (Stream<Path> paths = Files.list(root)) {
				Path pack = paths.filter(value -> value.getFileName().toString().endsWith(".zip"))
					.sorted(Comparator.comparing(value -> value.getFileName().toString())).findFirst().orElseThrow();
				return RenderTargetPlan.build(ShaderPack.load(pack, Map.of()));
			}
		} catch (IOException | RuntimeException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static final class FakeTexture extends GpuTexture {
		private boolean closed;

		private FakeTexture(String label) {
			super(USAGE_TEXTURE_BINDING | USAGE_RENDER_ATTACHMENT, label, GpuFormat.RGBA8_UNORM, 1280, 720, 1, 1);
		}

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
