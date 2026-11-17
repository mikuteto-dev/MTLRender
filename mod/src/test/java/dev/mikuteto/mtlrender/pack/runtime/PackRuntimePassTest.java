package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackRuntimePassTest {
	@Test
	void setupIsScheduledOnlyForLoadReloadAndResize() {
		assertEquals(List.of(PassKind.SETUP, PassKind.BEGIN, PassKind.SHADOWCOMP, PassKind.PREPARE,
			PassKind.DEFERRED, PassKind.COMPOSITE, PassKind.FINAL), PackRuntimeService.scheduledKinds(true));
		assertEquals(List.of(PassKind.BEGIN, PassKind.SHADOWCOMP, PassKind.PREPARE,
			PassKind.DEFERRED, PassKind.COMPOSITE, PassKind.FINAL), PackRuntimeService.scheduledKinds(false));
	}

	@Test
	void frameStagesHaveNoComputeOrGbufferEntry() {
		List<PackRuntimeService.PassInput> inputs = List.of(
			input(PassKind.FINAL, "final"), input(PassKind.COMPOSITE, "composite2"),
			input(PassKind.DEFERRED, "deferred"), input(PassKind.PREPARE, "prepare"),
			input(PassKind.SHADOWCOMP, "shadowcomp"), input(PassKind.BEGIN, "begin"));

		assertEquals(List.of("begin", "shadowcomp", "prepare", "deferred", "composite2", "final"),
			PackRuntimeService.scheduledKinds(false).stream()
				.map(kind -> inputs.stream().filter(value -> value.kind() == kind).findFirst())
				.flatMap(Optional::stream).map(value -> value.program().variantName()).toList());
	}

	@Test
	void vanillaSeedUsesOneColourAndThreeDeterministicDepthSnapshots() {
		assertEquals(List.of("colortex0", "depthtex0", "depthtex1", "depthtex2"),
			PackTargetSet.seedCommands(true, true));
	}

	@Test
	void finalFailureIsReturnedAsASurfacedResult() {
		RuntimeProgram program = new RuntimeProgram("overworld", "final", "final", 1L);
		PackProgramFailure failure = new PackProgramFailure("pack.zip", "overworld", "final",
			Program.Stage.FRAGMENT, "final unavailable");

		PackPassResult result = new PackPassFailurePolicy().classifyFailure(PassKind.FINAL, program, failure);

		assertTrue(result.surfacedFailure());
		assertEquals(failure, result.failure().orElseThrow());
	}

	private static PackRuntimeService.PassInput input(PassKind kind, String name) {
		return new PackRuntimeService.PassInput(kind, new RuntimeProgram("overworld", kind.sourceName(), name, 1L));
	}
}
