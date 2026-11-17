package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackFunctionConstantVariantsTest {
	@Test
	void returnsGenericFirstAndPublishesCompletedVariant() {
		Queue<Runnable> queue = new ArrayDeque<>();
		AtomicInteger compiled = new AtomicInteger();
		PackProgramArtifact generic = artifact("generic");
		PackFunctionConstantVariants variants = new PackFunctionConstantVariants(policy(4), generic,
			constants -> { compiled.incrementAndGet(); return artifact(constants.uniformConstants().toString()); }, queue::add);
		PackFunctionConstants requested = policy(4).requested(Map.of("QUALITY", "high"), true, true);

		PackFunctionConstantVariants.Selection first = variants.select(requested);
		assertEquals(generic, first.artifact());
		assertTrue(first.compiling());
		assertEquals(0, compiled.get());
		queue.remove().run();
		PackFunctionConstantVariants.Selection second = variants.select(requested);
		assertTrue(second.specialised());
		assertFalse(second.compiling());
		assertEquals(1, compiled.get());
	}

	@Test
	void separatesKeysCoalescesRequestsAndEnforcesCap() {
		Queue<Runnable> queue = new ArrayDeque<>();
		AtomicInteger compiled = new AtomicInteger();
		PackProgramArtifact generic = artifact("generic");
		PackVariantPolicy policy = policy(1);
		PackFunctionConstantVariants variants = new PackFunctionConstantVariants(policy, generic,
			constants -> { compiled.incrementAndGet(); return artifact("variant"); }, queue::add);
		PackFunctionConstants first = policy.requested(Map.of("QUALITY", "high"), false, true);
		PackFunctionConstants second = policy.requested(Map.of("QUALITY", "low"), false, true);

		assertTrue(variants.select(first).compiling());
		assertFalse(variants.select(first).specialised());
		assertFalse(variants.select(second).compiling());
		assertEquals(0, compiled.get());
		queue.remove().run();
		assertEquals(1, compiled.get());
	}

	@Test
	void descriptorUsesStableTypedValues() {
		PackVariantPolicy policy = policy(4);
		PackPipelineDesc.FunctionConstant[] values = PackPipelineDesc.typedConstants(
			policy.requested(Map.of("QUALITY", "3"), true, false)).toArray(PackPipelineDesc.FunctionConstant[]::new);
		assertEquals("QUALITY", values[0].name());
		assertEquals(PackPipelineDesc.FunctionConstantType.INT, values[0].type());
		assertEquals(3L, values[0].value());
		assertEquals(PackPipelineDesc.FunctionConstantType.BOOL, values[1].type());
		assertEquals(1L, values[1].value());
	}

	@Test
	void failedCompilationFallsBackWithoutRetrying() {
		Queue<Runnable> queue = new ArrayDeque<>();
		AtomicInteger compiled = new AtomicInteger();
		PackProgramArtifact generic = artifact("generic");
		PackVariantPolicy policy = policy(4);
		PackFunctionConstantVariants variants = new PackFunctionConstantVariants(policy, generic,
			constants -> { compiled.incrementAndGet(); throw new IllegalStateException("compile"); }, queue::add);
		PackFunctionConstants requested = policy.requested(Map.of(), true, false);

		assertTrue(variants.select(requested).compiling());
		queue.remove().run();
		assertFalse(variants.select(requested).compiling());
		assertEquals(1, compiled.get());
	}

	private static PackVariantPolicy policy(int cap) {
		return new PackVariantPolicy(java.util.Set.of("QUALITY"), cap);
	}

	private static PackProgramArtifact artifact(String name) {
		PackProgramRequest request = new PackProgramRequest("pack", "world", name,
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
		return new PackProgramArtifact(request, new RuntimeProgram("world", name, name, 1L),
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
	}
}
