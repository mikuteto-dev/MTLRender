package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullscreenPassTest {
	@Test
	void freezesPlanViewsActionsMipsAndBindings() throws IOException {
		RenderTargetPlan plan = plan("composite2");
		RenderTargetPlan.Pass original = plan.passes().get(0);
		RuntimeFrame.FlipSnapshot flip = new RuntimeFrame.FlipSnapshot("", "composite2",
			original.flippedBefore(), original.flippedAfter());
		RuntimeFrame frame = frame(plan, flip);
		PackProgramArtifact artifact = artifact("composite2", "composite", "composite2");
		FakeView main = new FakeView(320, 180);
		FakeView alternate = new FakeView(160, 90);
		Map<Integer, GpuTextureView> mainViews = new HashMap<>(Map.of(0, main));
		Map<Integer, GpuTextureView> alternateViews = new HashMap<>(Map.of(0, alternate));
		FullscreenPass.TargetViews views = new FullscreenPass.TargetViews(mainViews, alternateViews);
		Map<Integer, FullscreenPass.AttachmentAction> actions = new HashMap<>(Map.of(0,
			new FullscreenPass.AttachmentAction(FullscreenPass.LoadAction.LOAD, java.util.Optional.empty(),
				FullscreenPass.StoreAction.STORE)));
		PackUniformBindings bindings = bindings();
		FullscreenPass pass = new FullscreenPass(PassKind.COMPOSITE, artifact, frame, original, flip, views,
			bindings, actions, new FullscreenPass.Scissor(true, 2, 3, 40, 50));

		mainViews.clear();
		alternateViews.clear();
		actions.clear();
		assertEquals(Set.of(0), pass.logicalReads());
		assertEquals(Set.of(0), pass.logicalWrites());
		assertEquals(main, pass.reads().get(0).view());
		assertEquals(alternate, pass.writes().get(0).view());
		assertEquals(FullscreenPass.LoadAction.LOAD, pass.attachments().get(0).load());
		assertEquals(new FullscreenPass.Dimensions(320, 180), pass.dimensions());
		assertEquals(Set.of(), pass.requestedMipmaps());
		assertEquals(256, pass.uniformBindings().size());
		assertThrows(UnsupportedOperationException.class, () -> pass.logicalReads().add(4));
		assertThrows(UnsupportedOperationException.class, () -> pass.attachments().clear());
	}

	@Test
	void preservesNumberedAndLetteredProgramIdentity() throws IOException {
		RenderTargetPlan plan = plan("composite12_a");
		RenderTargetPlan.Pass targetPass = plan.passes().get(0);
		RuntimeFrame.FlipSnapshot flip = new RuntimeFrame.FlipSnapshot("", "composite12_a",
			targetPass.flippedBefore(), targetPass.flippedAfter());
		FullscreenPass pass = new FullscreenPass(PassKind.COMPOSITE,
			artifact("composite12_a", "composite", "composite12_a"), frame(plan, flip), targetPass, flip,
			views(), bindings(), new FullscreenPass.Scissor(false, 0, 0, 0, 0));

		assertEquals("composite", pass.program().sourceName());
		assertEquals("composite12_a", pass.program().variantName());
		assertEquals("composite12_a", pass.targetPlan().name());
	}

	@Test
	void rejectsComputeOnlyAndMismatchedDescriptors() throws IOException {
		assertThrows(IllegalArgumentException.class, () -> new PackProgramRequest("pack", "", "composite2",
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f", Program.Stage.COMPUTE, "c")));
		RenderTargetPlan plan = plan("composite2");
		RenderTargetPlan.Pass targetPass = plan.passes().get(0);
		RuntimeFrame.FlipSnapshot flip = new RuntimeFrame.FlipSnapshot("", "composite2", Set.of(), Set.of());
		PackProgramArtifact mismatch = artifact("composite2", "composite", "composite3");
		assertThrows(IllegalArgumentException.class, () -> new FullscreenPass(PassKind.COMPOSITE, mismatch,
			frame(plan, flip), targetPass, flip, views(), bindings(), FullscreenPass.Scissor.disabled()));
		assertThrows(IllegalArgumentException.class, () -> new FullscreenPass(PassKind.FINAL,
			artifact("composite2", "composite", "composite2"), frame(plan, flip), targetPass, flip,
			views(), bindings(), FullscreenPass.Scissor.disabled()));
	}

	@Test
	void freezesSourceSnapshotSets() throws IOException {
		RenderTargetPlan plan = plan("composite2");
		Set<Integer> reads = new HashSet<>(Set.of(0));
		Set<Integer> writes = new HashSet<>(Set.of(0));
		Set<String> samplers = new HashSet<>(Set.of("gcolor"));
		Set<Integer> readAlt = new HashSet<>();
		Set<Integer> writeAlt = new HashSet<>();
		Set<Integer> mips = new HashSet<>(Set.of(0));
		Set<Integer> before = new HashSet<>();
		Set<Integer> after = new HashSet<>();
		RenderTargetPlan.Pass source = new RenderTargetPlan.Pass("", "composite2", reads, writes, samplers,
			readAlt, writeAlt, mips, new RenderTargetPlan.PassScale(1.0f, 0.0f, 0.0f), before, after);
		RuntimeFrame.FlipSnapshot flip = new RuntimeFrame.FlipSnapshot("", "composite2", before, after);
		FullscreenPass pass = new FullscreenPass(PassKind.COMPOSITE, artifact("composite2", "composite", "composite2"),
			frame(plan, flip), source, flip, views(), bindings(), FullscreenPass.Scissor.disabled());

		reads.clear();
		writes.clear();
		samplers.clear();
		mips.clear();
		assertEquals(Set.of(0), pass.logicalReads());
		assertEquals(Set.of(0), pass.logicalWrites());
		assertEquals(Set.of("gcolor"), pass.targetPlan().samplers());
		assertEquals(Set.of(0), pass.requestedMipmaps());
		assertFalse(pass.reads().isEmpty());
		assertTrue(pass.flips().before().isEmpty());
	}

	private static PackUniformBindings bindings() {
		return new PackUniformBindings(List.of(new Reflection.UniformMember("time", 4, 0, 4, 0)));
	}

	private static FullscreenPass.TargetViews views() {
		return new FullscreenPass.TargetViews(Map.of(0, new FakeView(320, 180)),
			Map.of(0, new FakeView(320, 180)));
	}

	private static RuntimeFrame frame(RenderTargetPlan plan, RuntimeFrame.FlipSnapshot flip) {
		return new RuntimeFrame(320, 180, new RuntimeFrame.Viewport(0, 0, 320, 180),
			new RuntimeFrame.Scale(1.0f, 1.0f), List.of(flip), plan);
	}

	private static PackProgramArtifact artifact(String program, String sourceName, String variant) {
		PackProgramRequest request = new PackProgramRequest("pack", "", program,
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
		return new PackProgramArtifact(request, new RuntimeProgram("", sourceName, variant, 9L),
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
	}

	private static RenderTargetPlan plan(String program) throws IOException {
		Path path = Files.createTempFile("mtlrender-fullscreen", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (String suffix : List.of("vsh", "fsh")) {
				output.putNextEntry(new ZipEntry("shaders/" + program + "." + suffix));
				output.write((suffix.equals("fsh")
					? "/" + "* DRAWBUFFERS:0 *" + "/ const bool colortex0MipmapEnabled = true; uniform sampler2D gcolor;"
					: "void main(){}").getBytes());
				output.closeEntry();
			}
		}
		return RenderTargetPlan.build(ShaderPack.load(path, Map.of()));
	}

	private static final class FakeView extends GpuTextureView {
		private final int width;
		private final int height;
		private boolean closed;

		private FakeView(int width, int height) {
			super(null, 0, 1);
			this.width = width;
			this.height = height;
		}

		@Override
		public void close() {
			closed = true;
		}

		@Override
		public int getWidth(int mipLevel) {
			return width;
		}

		@Override
		public int getHeight(int mipLevel) {
			return height;
		}

		@Override
		public boolean isClosed() {
			return closed;
		}
	}
}
