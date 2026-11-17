package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackPassExecutorTest {
	@Test
	void optionalFailureIsSkippedAndNumberedProgramIsRetained() throws Exception {
		FullscreenPass pass = pass("composite2");
		PackProgramFailure failure = failure(pass.program().variantName());

		PackPassResult result = new PackPassExecutor().execute(pass, failure);

		assertTrue(result.skipped());
		assertEquals("composite2", result.program().variantName());
	}

	@Test
	void finalFailureIsSurfaced() throws Exception {
		FullscreenPass pass = pass("final");
		PackProgramFailure failure = failure(pass.program().variantName());
		List<PackPassResult> reported = new ArrayList<>();
		PackPassExecutor executor = new PackPassExecutor(new PackPassFailurePolicy(), new RecordingSequence(), reported::add);

		PackPassResult result = executor.executeSequence(List.of(PackPassExecutor.RequestedPass.failed(pass, failure))).getFirst();

		assertTrue(resultAt(reported, 0).surfacedFailure());
		assertTrue(result.surfacedFailure());
		assertEquals(failure, result.failure().orElseThrow());
	}

	@Test
	void mipmapIsRecordedBetweenProducerAndNumberedConsumerInOneSequence() throws Exception {
		RenderTargetPlan plan = plan(Map.of(
			"composite", "const bool colortex0MipmapEnabled = true; /* DRAWBUFFERS:0 */ void main(){}",
			"composite1", "uniform sampler2D colortex0; /* DRAWBUFFERS:1 */ void main(){}"));
		FullscreenPass producer = pass(plan, "composite", 11L);
		FullscreenPass consumer = pass(plan, "composite1", 12L);
		PackPassPlan producerPlan = PackPassPlan.capture(producer, pipeline(), Map.of());
		PackPassPlan consumerPlan = PackPassPlan.capture(consumer, pipeline("colortex0"), Map.of("colortex0", producer.writes().get(0).view()));
		RecordingSequence recorder = new RecordingSequence();

		List<PackPassResult> results = new PackPassExecutor(recorder).executeSequence(List.of(
			PackPassExecutor.RequestedPass.success(producer, producerPlan, slice()),
			PackPassExecutor.RequestedPass.success(consumer, consumerPlan, slice())));

		assertEquals(List.of("pass:composite", "mip:0:4", "pass:composite1"), recorder.events);
		assertEquals(0, recorder.submitCount);
		assertEquals(0, recorder.presentCount);
		assertEquals(2, results.size());
		assertEquals(producer.flips().before(), producerPlan.flips().before());
		assertEquals(producer.flips().after(), producerPlan.flips().after());
	}

	@Test
	void disabledAndOneLevelMipmapsAreOmitted() throws Exception {
		assertNoMipmap("false", 4);
		assertNoMipmap("true", 1);
	}

	@Test
	void optionalFailureIsLoggedAndDoesNotBlockAnUnrelatedProgram() throws Exception {
		RenderTargetPlan plan = plan(Map.of(
			"composite", "/* DRAWBUFFERS:0 */ void main(){}",
			"composite2", "/* DRAWBUFFERS:1 */ void main(){}"));
		FullscreenPass failed = pass(plan, "composite", 21L);
		FullscreenPass usable = pass(plan, "composite2", 22L);
		PackPassPlan usablePlan = PackPassPlan.capture(usable, pipeline(), Map.of());
		RecordingSequence recorder = new RecordingSequence();
		List<PackPassResult> reported = new ArrayList<>();
		PackPassExecutor executor = new PackPassExecutor(new PackPassFailurePolicy(), recorder, reported::add);

		List<PackPassResult> results = executor.executeSequence(List.of(
			PackPassExecutor.RequestedPass.failed(failed, failure(failed.program().variantName())),
			PackPassExecutor.RequestedPass.success(usable, usablePlan, slice())));

		assertEquals(List.of("pass:composite2"), recorder.events);
		assertTrue(results.get(0).skipped());
		assertTrue(results.get(1).succeeded());
		assertEquals(List.of(PackPassResult.Action.OPTIONAL_SKIP), reported.stream().map(PackPassResult::action).toList());
	}

	private static void assertNoMipmap(String enabled, int mipLevels) throws Exception {
		RenderTargetPlan plan = plan(Map.of(
			"composite", "const bool colortex0MipmapEnabled = " + enabled + "; /* DRAWBUFFERS:0 */ void main(){}",
			"composite1", "uniform sampler2D colortex0; /* DRAWBUFFERS:1 */ void main(){}"));
		FullscreenPass producer = pass(plan, "composite", 31L, mipLevels);
		FullscreenPass consumer = pass(plan, "composite1", 32L, mipLevels);
		PackPassPlan producerPlan = PackPassPlan.capture(producer, pipeline(), Map.of());
		PackPassPlan consumerPlan = PackPassPlan.capture(consumer, pipeline("colortex0"), Map.of("colortex0", producer.writes().get(0).view()));
		RecordingSequence recorder = new RecordingSequence();

		new PackPassExecutor(recorder).executeSequence(List.of(
			PackPassExecutor.RequestedPass.success(producer, producerPlan, slice()),
			PackPassExecutor.RequestedPass.success(consumer, consumerPlan, slice())));

		assertEquals(List.of("pass:composite", "pass:composite1"), recorder.events);
	}

	private static PackPassResult resultAt(List<PackPassResult> results, int index) {
		return results.get(index);
	}

	private static GpuBufferSlice slice() {
		return new GpuBufferSlice(null, 0, 0);
	}

	private static PackProgramFailure failure(String program) {
		return new PackProgramFailure("test", "", program, Program.Stage.FRAGMENT, "compile failed");
	}

	private static FullscreenPass pass(String name) throws Exception {
		return pass(plan(Map.of(name, "/* DRAWBUFFERS:0 */ void main(){}")), name, 1L);
	}

	private static FullscreenPass pass(RenderTargetPlan plan, String name, long pipeline) {
		return pass(plan, name, pipeline, 4);
	}

	private static FullscreenPass pass(RenderTargetPlan plan, String name, long pipeline, int mipLevels) {
		RenderTargetPlan.Pass target = plan.passes().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
		RuntimeProgram program = new RuntimeProgram("", name.equals("final") ? "final" : "composite", name, pipeline);
		PackProgramArtifact artifact = new PackProgramArtifact(
			new PackProgramRequest("test", "", name, Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f")),
			program, Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
		List<RuntimeFrame.FlipSnapshot> flips = plan.passes().stream()
			.map(value -> new RuntimeFrame.FlipSnapshot("", value.name(), value.flippedBefore(), value.flippedAfter())).toList();
		RuntimeFrame frame = new RuntimeFrame(16, 16, new RuntimeFrame.Viewport(0, 0, 16, 16),
			new RuntimeFrame.Scale(1, 1), flips, plan);
		Map<Integer, GpuTextureView> views = Map.of(0, new FakeView(16, 16, mipLevels), 1, new FakeView(16, 16, mipLevels));
		return new FullscreenPass(name.equals("final") ? PassKind.FINAL : PassKind.COMPOSITE, artifact, frame,
			target, frame.flips().stream().filter(value -> value.passName().equals(name)).findFirst().orElseThrow(),
			new FullscreenPass.TargetViews(views, views), new PackUniformBindings(List.of(new Reflection.UniformMember("time", 4, 0, 4, 0))),
			FullscreenPass.Scissor.disabled());
	}

	private static PackPipelineDesc pipeline(String sampler) {
		return new PackPipelineDesc("v", "vertex", "f", "fragment",
			List.of(new PackPipelineDesc.Binding(sampler, PackPipelineDesc.Kind.TEXTURE, 0, PackPipelineDesc.STAGE_FRAGMENT)),
			null, List.of(), null, null, false, PackPipelineDesc.CullMode.NONE);
	}

	private static PackPipelineDesc pipeline() {
		return new PackPipelineDesc("v", "vertex", "f", "fragment", List.of(), null, List.of(), null, null, false,
			PackPipelineDesc.CullMode.NONE);
	}

	private static RenderTargetPlan plan(Map<String, String> fragments) throws Exception {
		Path path = Files.createTempFile("mtlrender-executor", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			for (Map.Entry<String, String> entry : fragments.entrySet()) {
				output.putNextEntry(new ZipEntry("shaders/" + entry.getKey() + ".vsh"));
				output.write("void main(){}".getBytes(StandardCharsets.UTF_8));
				output.closeEntry();
				output.putNextEntry(new ZipEntry("shaders/" + entry.getKey() + ".fsh"));
				output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				output.closeEntry();
			}
		}
		return RenderTargetPlan.build(ShaderPack.load(path, Map.of()));
	}

	private static final class FakeView extends GpuTextureView {
		private final int width;
		private final int height;

		private FakeView(int width, int height, int mipLevels) {
			super(null, 0, mipLevels);
			this.width = width;
			this.height = height;
		}

		@Override public void close() { }
		@Override public boolean isClosed() { return false; }
		@Override public int getWidth(int mipLevel) { return width; }
		@Override public int getHeight(int mipLevel) { return height; }
	}

	private static final class RecordingSequence implements PackPassExecutor.SequenceRecorder {
		private final List<String> events = new ArrayList<>();
		private int submitCount;
		private int presentCount;

		@Override
		public void render(FullscreenPass preparedPass, PackPassPlan plan, GpuBufferSlice uniformSlice) {
			events.add("pass:" + preparedPass.program().variantName());
		}

		@Override
		public void generateMipmaps(GpuTextureView target, int firstMip, int mipCount) {
			events.add("mip:" + firstMip + ":" + mipCount);
		}
	}
}
