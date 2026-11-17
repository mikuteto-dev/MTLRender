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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PackPassFailurePolicyTest {
	private final PackPassFailurePolicy policy = new PackPassFailurePolicy();

	@Test
	void successIsExplicit() throws IOException {
		FullscreenPass pass = pass("composite2");
		PackPassResult result = policy.classifySuccess(PassKind.COMPOSITE, pass);

		assertEquals(PackPassResult.Action.SUCCESS, result.action());
		assertEquals(pass.program(), result.program());
		assertEquals(pass, result.pass().orElseThrow());
	}

	@Test
	void optionalFailureBecomesSkipAndPreservesContext() {
		RuntimeProgram program = program("composite2_a");
		PackProgramFailure failure = failure("composite2_a", Program.Stage.FRAGMENT);

		PackPassResult result = policy.classifyFailure(PassKind.COMPOSITE, program, failure);

		assertEquals(PackPassResult.Action.OPTIONAL_SKIP, result.action());
		assertEquals(failure, result.failure().orElseThrow());
		assertEquals("first error", result.failure().orElseThrow().normalizedFirstError());
	}

	@Test
	void finalFailureIsSurfacedWithoutFallback() {
		RuntimeProgram program = program("final");
		PackProgramFailure failure = failure("final", Program.Stage.VERTEX);

		PackPassResult result = policy.classifyFailure(PassKind.FINAL, program, failure);

		assertEquals(PackPassResult.Action.FINAL_FAILURE, result.action());
		assertEquals(program, result.program());
		assertEquals(failure, result.failure().orElseThrow());
	}

	@Test
	void variantsAreMatchedByIdentity() {
		RuntimeProgram numbered = program("composite1");
		RuntimeProgram lettered = program("composite_a");
		PackProgramFailure numberedFailure = failure("composite1", Program.Stage.FRAGMENT);
		PackProgramFailure letteredFailure = failure("composite_a", Program.Stage.FRAGMENT);

		assertEquals(PackPassResult.Action.OPTIONAL_SKIP,
			policy.classifyFailure(PassKind.COMPOSITE, numbered, numberedFailure).action());
		assertEquals(PackPassResult.Action.OPTIONAL_SKIP,
			policy.classifyFailure(PassKind.COMPOSITE, lettered, letteredFailure).action());
		assertThrows(IllegalArgumentException.class,
			() -> policy.classifyFailure(PassKind.COMPOSITE, numbered, letteredFailure));
	}

	@Test
	void outcomesCannotMixPassAndFailure() {
		RuntimeProgram program = program("composite");
		assertThrows(IllegalArgumentException.class, () -> new PackPassResult(
			PackPassResult.Action.SUCCESS, PassKind.COMPOSITE, program,
			Optional.empty(), Optional.of(failure("composite", Program.Stage.FRAGMENT))));
	}

	private static RuntimeProgram program(String variant) {
		return new RuntimeProgram("overworld", "composite", variant, 1L);
	}

	private static PackProgramFailure failure(String program, Program.Stage stage) {
		return new PackProgramFailure("pack.zip", "overworld", program, stage, " first error\nsecond error");
	}

	private static FullscreenPass pass(String program) throws IOException {
		RenderTargetPlan plan = plan(program);
		RenderTargetPlan.Pass target = plan.passes().getFirst();
		RuntimeFrame.FlipSnapshot flip = new RuntimeFrame.FlipSnapshot("", program,
			target.flippedBefore(), target.flippedAfter());
		PackProgramRequest request = new PackProgramRequest("pack", "", program,
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
		PackProgramArtifact artifact = new PackProgramArtifact(request,
			new RuntimeProgram("", "composite", program, 1L),
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
		RuntimeFrame frame = new RuntimeFrame(320, 180, new RuntimeFrame.Viewport(0, 0, 320, 180),
			new RuntimeFrame.Scale(1.0f, 1.0f), List.of(flip), plan);
		return new FullscreenPass(PassKind.COMPOSITE, artifact, frame, target, flip,
			new FullscreenPass.TargetViews(Map.of(0, new FakeView()), Map.of(0, new FakeView())),
			new PackUniformBindings(List.of(new Reflection.UniformMember("time", 4, 0, 4, 0))),
			FullscreenPass.Scissor.disabled());
	}

	private static RenderTargetPlan plan(String program) throws IOException {
		Path path = Files.createTempFile("mtlrender-pass", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (String suffix : List.of("vsh", "fsh")) {
				output.putNextEntry(new ZipEntry("shaders/" + program + "." + suffix));
				output.write((suffix.equals("fsh") ? "/* DRAWBUFFERS:0 */ uniform sampler2D gcolor;" : "void main(){}").getBytes());
				output.closeEntry();
			}
		}
		return RenderTargetPlan.build(ShaderPack.load(path, Map.of()));
	}

	private static final class FakeView extends GpuTextureView {
		private FakeView() { super(null, 0, 1); }
		@Override public void close() { }
		@Override public int getWidth(int mipLevel) { return 320; }
		@Override public int getHeight(int mipLevel) { return 180; }
		@Override public boolean isClosed() { return false; }
	}
}
