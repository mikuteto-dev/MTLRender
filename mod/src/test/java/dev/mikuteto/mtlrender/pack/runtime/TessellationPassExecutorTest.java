package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.tess.TessellationBuffers;
import dev.mikuteto.mtlrender.pack.tess.TessellationDescriptor;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TessellationPassExecutorTest {
	@Test
	void absentProgramRecordsNothing() {
		Recording recorder = new Recording();
		assertFalse(new TessellationPassExecutor(recorder).execute(Optional.empty()).recorded());
		assertEquals(List.of(), recorder.events);
	}

	@Test
	void recordsComputeBarrierIndexedTesDrawAndClosesOnlyTheRenderPass() throws Exception {
		PreparedFixture fixture = fixture("composite2");
		Recording recorder = new Recording();
		TessellationPassExecutor.Result result = new TessellationPassExecutor(recorder).execute(fixture.prepared);
		assertTrue(result.recorded());
		assertEquals(List.of("begin", "pipeline:11", "dispatch:2", "pipeline:12", "dispatch:2", "end", "barrier",
			"render:composite2", "bind", "vertex:192", "index:384", "draw:12:1:0:0:0", "close"), recorder.events);
		assertEquals(0, recorder.submitCount);
		assertEquals(0, recorder.presentCount);
		fixture.buffers.close();
	}

	@Test
	void stageFailureDoesNotFallbackToNormalDraw() throws Exception {
		PreparedFixture fixture = fixture("composite_a");
		Recording recorder = new Recording();
		recorder.failRender = true;
		TessellationPassExecutor.Result result = new TessellationPassExecutor(recorder).execute(fixture.prepared);
		assertFalse(result.recorded());
		assertTrue(result.failure().isPresent());
		assertEquals(List.of("begin", "pipeline:11", "dispatch:2", "pipeline:12", "dispatch:2", "end", "barrier",
			"render:composite_a", "bind", "close"), recorder.events);
		fixture.buffers.close();
	}

	private static PreparedFixture fixture(String name) throws Exception {
		Path path = Files.createTempFile("mtlrender-tess", ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			for (String extension : List.of("vsh", "tcs", "tes", "fsh")) {
				zip.putNextEntry(new ZipEntry("shaders/" + name + "." + extension));
				zip.write("void main(){}".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		RenderTargetPlan targetPlan = RenderTargetPlan.build(ShaderPack.load(path, Map.of()));
		RenderTargetPlan.Pass target = targetPlan.passes().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
		RuntimeProgram runtime = new RuntimeProgram(target.dimension(), "composite", name, 19L);
		PackProgramRequest request = new PackProgramRequest("test", target.dimension(), name, Map.of(
			Program.Stage.VERTEX, "v", Program.Stage.TESS_CONTROL, "tc", Program.Stage.TESS_EVALUATION, "te", Program.Stage.FRAGMENT, "f"));
		PackProgramArtifact artifact = new PackProgramArtifact(request, runtime, Map.of(
			Program.Stage.VERTEX, "v", Program.Stage.TESS_CONTROL, "tc", Program.Stage.TESS_EVALUATION, "te", Program.Stage.FRAGMENT, "f"));
		RuntimeFrame frame = new RuntimeFrame(16, 16, new RuntimeFrame.Viewport(0, 0, 16, 16), new RuntimeFrame.Scale(1, 1),
			targetPlan.passes().stream().map(value -> new RuntimeFrame.FlipSnapshot(value.dimension(), value.name(), value.flippedBefore(), value.flippedAfter())).toList(), targetPlan);
		FullscreenPass pass = new FullscreenPass(PassKind.COMPOSITE, artifact, frame, target,
			frame.flips().stream().filter(value -> value.passName().equals(name)).findFirst().orElseThrow(),
			new FullscreenPass.TargetViews(Map.of(0, new FakeView()), Map.of(0, new FakeView())),
			new PackUniformBindings(List.of(new Reflection.UniformMember("u", 4, 0, 4, 0))), FullscreenPass.Scissor.disabled());
		PackPassPlan plan = PackPassPlan.capture(pass, new dev.mikuteto.mtlrender.backend.PackPipelineDesc("v", "vertex", "f", "fragment", List.of(), null, List.of(), null, null, false, dev.mikuteto.mtlrender.backend.PackPipelineDesc.CullMode.NONE), Map.of());
		TessellationBuffers buffers = new TessellationBuffers(2, 32, 12, 12, (ignored, size, usage) -> new FakeBuffer(usage, size));
		TessellationDescriptor descriptor = new TessellationDescriptor(TessellationDescriptor.Topology.TRIANGLES, TessellationDescriptor.Spacing.EQUAL, 3, 6, new int[] {0, 1, 2}, new int[] {0}, name);
		return new PreparedFixture(buffers, new TessellationPassExecutor.Prepared(request, plan, descriptor, 11, 12, 2, 1, 1, buffers, 12, 12, new byte[16], new GpuBufferSlice(null, 0, 0)));
	}

	private record PreparedFixture(TessellationBuffers buffers, TessellationPassExecutor.Prepared prepared) { }

	private static final class Recording implements TessellationPassExecutor.Recorder {
		private final List<String> events = new ArrayList<>();
		private int submitCount;
		private int presentCount;
		private boolean failRender;
		@Override public void begin(boolean concurrent) { events.add("begin"); }
		@Override public void pipeline(long handle) { events.add("pipeline:" + handle); }
		@Override public void buffer(int slot, GpuBuffer buffer, long offset, MetalAccess.Access access) { }
		@Override public void bytes(int slot, byte[] data) { }
		@Override public void dispatch(int x, int y, int z) { events.add("dispatch:" + x); }
		@Override public void end() { events.add("end"); }
		@Override public void barrier() { events.add("barrier"); }
		@Override public void beginRender(PackPassPlan plan, TessellationDescriptor descriptor) { events.add("render:" + plan.program().variantName()); }
		@Override public void bindResources(PackPassPlan plan, GpuBufferSlice uniformSlice) { events.add("bind"); if (failRender) throw new IllegalStateException("bind failed"); }
		@Override public void endRender() { events.add("close"); }
		@Override public void vertex(GpuBufferSlice slice) { events.add("vertex:" + slice.length()); }
		@Override public void index(GpuBuffer buffer) { events.add("index:" + buffer.size()); }
		@Override public void indexed(int count, int instances, int firstIndex, int baseVertex, int firstInstance) { events.add("draw:" + count + ":" + instances + ":" + firstIndex + ":" + baseVertex + ":" + firstInstance); }
	}

	private static final class FakeBuffer extends GpuBuffer {
		private FakeBuffer(int usage, long size) { super(usage, size); }
		@Override public boolean isClosed() { return false; }
		@Override public void close() { }
		@Override public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) { return null; }
	}

	private static final class FakeView extends GpuTextureView {
		private FakeView() { super(null, 0, 1); }
		@Override public void close() { }
		@Override public boolean isClosed() { return false; }
		@Override public int getWidth(int mipLevel) { return 16; }
		@Override public int getHeight(int mipLevel) { return 16; }
	}
}
