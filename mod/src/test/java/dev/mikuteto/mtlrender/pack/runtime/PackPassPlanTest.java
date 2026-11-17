package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackPassPlanTest {
	@Test
	void resolvesLegacySamplerAndFreezesFlipSnapshot() throws IOException {
		RenderTargetPlan plan = plan("composite2", "gcolor");
		RenderTargetPlan.Pass target = plan.passes().get(0);
		RuntimeFrame.FlipSnapshot flip = new RuntimeFrame.FlipSnapshot("", target.name(), target.flippedBefore(), target.flippedAfter());
		FullscreenPass pass = new FullscreenPass(PassKind.COMPOSITE, artifact("composite2"),
			new RuntimeFrame(320, 180, new RuntimeFrame.Viewport(0, 0, 320, 180),
				new RuntimeFrame.Scale(0.5f, 0.5f), List.of(flip), plan), target, flip,
			new FullscreenPass.TargetViews(Map.of(0, new FakeView(160, 90)), Map.of(0, new FakeView(160, 90))),
			bindings(), Map.of(0, new FullscreenPass.AttachmentAction(FullscreenPass.LoadAction.LOAD, java.util.Optional.empty(), FullscreenPass.StoreAction.STORE)),
			new FullscreenPass.Scissor(true, -2, -1, 200, 200));
		PackPassPlan result = PackPassPlan.capture(pass, pipeline("gcolor"), Map.of("colortex0", pass.reads().get(0).view()));
		assertEquals(160, result.viewport().width());
		assertEquals(90, result.scissor().height());
		assertEquals(pass.flips().before(), result.flips().before());
		assertEquals(pass.reads().get(0).view(), result.samplers().get("gcolor").view());
	}

	@Test
	void rejectsSamePhysicalReadWriteAttachment() throws IOException {
		RenderTargetPlan plan = plan("composite2", "gcolor");
		RenderTargetPlan.Pass target = plan.passes().get(0);
		RuntimeFrame.FlipSnapshot flip = new RuntimeFrame.FlipSnapshot("", target.name(), target.flippedBefore(), target.flippedAfter());
		FakeView view = new FakeView(320, 180);
		FullscreenPass pass = new FullscreenPass(PassKind.COMPOSITE, artifact("composite2"),
			new RuntimeFrame(320, 180, new RuntimeFrame.Viewport(0, 0, 320, 180), new RuntimeFrame.Scale(1, 1), List.of(flip), plan),
			target, flip, new FullscreenPass.TargetViews(Map.of(0, view), Map.of(0, view)), bindings(),
			Map.of(0, new FullscreenPass.AttachmentAction(FullscreenPass.LoadAction.LOAD, java.util.Optional.empty(), FullscreenPass.StoreAction.STORE)),
			FullscreenPass.Scissor.disabled());
		assertThrows(IllegalArgumentException.class, () -> PackPassPlan.capture(pass, pipeline("gcolor"), Map.of("colortex0", view)));
	}

	private static PackPipelineDesc pipeline(String sampler) {
		return new PackPipelineDesc("v", "vertex", "f", "fragment",
			List.of(new PackPipelineDesc.Binding(sampler, PackPipelineDesc.Kind.TEXTURE, 2, PackPipelineDesc.STAGE_FRAGMENT)),
			null, List.of(), null, null, false, PackPipelineDesc.CullMode.NONE);
	}

	private static PackUniformBindings bindings() {
		return new PackUniformBindings(List.of(new Reflection.UniformMember("time", 4, 0, 4, 0)));
	}

	private static PackProgramArtifact artifact(String name) {
		return new PackProgramArtifact(new PackProgramRequest("pack", "", name,
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f")),
			new RuntimeProgram("", "composite", name, 1),
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
	}

	private static RenderTargetPlan plan(String name, String sampler) throws IOException {
		Path path = Files.createTempFile("mtlrender-pass-plan", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			output.putNextEntry(new ZipEntry("shaders/" + name + ".vsh"));
			output.write("void main(){}".getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
			output.putNextEntry(new ZipEntry("shaders/" + name + ".fsh"));
			output.write(("/* DRAWBUFFERS:0 */ uniform sampler2D " + sampler + ";").getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
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

		@Override public void close() { closed = true; }
		@Override public int getWidth(int mipLevel) { return width; }
		@Override public int getHeight(int mipLevel) { return height; }
		@Override public boolean isClosed() { return closed; }
	}
}
