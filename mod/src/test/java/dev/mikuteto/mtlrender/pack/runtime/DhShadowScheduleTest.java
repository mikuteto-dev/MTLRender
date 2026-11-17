package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.dh.DhShadowFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class DhShadowScheduleTest {
	@Test
	void fullscreenOrderLeavesShadowBeforeShadowcomp() {
		assertEquals(List.of(PassKind.SETUP, PassKind.BEGIN, PassKind.SHADOWCOMP, PassKind.PREPARE,
			PassKind.DEFERRED, PassKind.COMPOSITE, PassKind.FINAL), PackRuntimeService.scheduledKinds(true));
		assertEquals(List.of(PassKind.BEGIN, PassKind.SHADOWCOMP, PassKind.PREPARE,
			PassKind.DEFERRED, PassKind.COMPOSITE, PassKind.FINAL), PackRuntimeService.scheduledKinds(false));
	}

	@Test
	void shadowExecutorSeamRequiresCaptureBeforeDrawAndDepthFinalization() {
		List<String> events = new ArrayList<>();
		PackPassExecutor.ShadowStage stage = new PackPassExecutor.ShadowStage() {
			@Override
			public PackPassExecutor.ShadowInput capture(PackRuntimeService.PackSnapshot pack, long generation,
				PackTargetSet targets) {
				events.add("capture");
				return null;
			}

			@Override
			public void render(DhShadowFrame frame, PackTargetSet targets, com.mojang.blaze3d.systems.CommandEncoder encoder) {
				events.add("render");
			}

			@Override
			public void finalizeDepth(DhShadowFrame frame, PackTargetSet targets,
				com.mojang.blaze3d.systems.CommandEncoder encoder) {
				events.add("depth");
			}
		};
		assertEquals(0, events.size());
		assertFalse(DhShadowFrame.disabled().enabled());
		assertSame(stage, new PackRuntimeService.RuntimeResources(Optional.empty(), Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.empty(), Optional.of(stage)).shadowStage().orElseThrow());
	}

	@Test
	void noPackReturnsBeforeOptionalShadowStage() {
		PackRuntimeService service = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override public boolean configured() { return false; }
			@Override public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() { return Optional.empty(); }
			@Override public long version() { return 1L; }
		}, PackRuntime.inactive());
		assertFalse(service.render(null, null).isPresent());
		service.close();
	}
}
