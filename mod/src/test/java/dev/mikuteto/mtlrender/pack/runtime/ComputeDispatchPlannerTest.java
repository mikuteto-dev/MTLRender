package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.glsl.PackShaderReflection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComputeDispatchPlannerTest {
	private static final ComputeDispatchPlanner.FrameSizeContext SIZES = new ComputeDispatchPlanner.FrameSizeContext(
		new ComputeDispatchPlanner.FrameSize(1920, 1080),
		new ComputeDispatchPlanner.FrameSize(1280, 720),
		new ComputeDispatchPlanner.FrameSize(2048, 1024));
	private static final PackShaderReflection.ComputeLocalSize LOCAL =
		new PackShaderReflection.ComputeLocalSize(16, 8, 2);

	@Test
	void defaultsUseTargetAndAllAxes() {
		ComputeDispatchPlanner.Result result = resolve("composite", "");
		assertEquals(new ComputeDispatchPlanner.Direct(80, 90, 1), result);
	}

	@Test
	void absoluteGroupsAreReturnedUnchanged() {
		ComputeDispatchPlanner.Result result = resolve("setup", "workGroups.setup=3 4 5");
		assertEquals(new ComputeDispatchPlanner.Direct(3, 4, 5), result);
	}

	@Test
	void renderRelativeUsesRenderDimensions() {
		ComputeDispatchPlanner.Result result = resolve("deferred", "workGroupsRender.deferred=0.5 0.25");
		assertEquals(new ComputeDispatchPlanner.Direct(60, 34, 1), result);
	}

	@Test
	void shadowUsesShadowDimensions() {
		ComputeDispatchPlanner.Result result = resolve("shadowcomp2_a", "");
		assertEquals(new ComputeDispatchPlanner.Direct(128, 128, 1), result);
	}

	@Test
	void exactVariantPrecedesBaseVariant() {
		ComputeDispatchPlanner.Result result = resolve("composite_a",
			"workGroups.composite=1 1 1\nworkGroups.composite_a=2 3 4");
		assertEquals(new ComputeDispatchPlanner.Direct(2, 3, 4), result);
	}

	@Test
	void indirectReturnsResourceAndOffset() {
		ComputeDispatchPlanner.Result result = resolve("final", "indirect.final=7 16\nbufferObject.7=64 dispatchArgs");
		assertEquals(new ComputeDispatchPlanner.IndirectResult("dispatchArgs", 16), result);
	}

	@Test
	void rejectsInvalidInputs() {
		assertThrows(IllegalArgumentException.class, () -> resolve("x", "workGroups.x=0 1 1"));
		assertThrows(IllegalArgumentException.class, () -> resolve("x", "", new PackShaderReflection.ComputeLocalSize(0, 1, 1)));
		assertThrows(IllegalArgumentException.class, () -> resolve("x", "workGroups.x=70000 1 1"));
		assertThrows(IllegalArgumentException.class, () -> resolve("x", "indirect.x=3 0"));
	}

	private static ComputeDispatchPlanner.Result resolve(String name, String source) {
		return resolve(name, source, LOCAL);
	}

	private static ComputeDispatchPlanner.Result resolve(String name, String source,
			PackShaderReflection.ComputeLocalSize local) {
		Program program = new Program(name, Map.of(Program.Stage.COMPUTE, "void main() {}"));
		return ComputeDispatchPlanner.resolve(program, new ShaderProperties(source, source),
			ComputePlan.empty(), local, SIZES);
	}
}
