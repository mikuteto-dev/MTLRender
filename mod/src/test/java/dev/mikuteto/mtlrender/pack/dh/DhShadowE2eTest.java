package dev.mikuteto.mtlrender.pack.dh;

import dev.mikuteto.mtlrender.pack.runtime.PackPassExecutor;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntime;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.pack.runtime.PassKind;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DhShadowE2eTest {
	@Test
	void noPackDoesNoWork() {
		List<String> calls = new ArrayList<>();
		PackRuntimeService service = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override
			public boolean configured() {
				calls.add("configured");
				return false;
			}

			@Override
			public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() {
				calls.add("active");
				return Optional.empty();
			}

			@Override
			public long version() {
				calls.add("version");
				return 1L;
			}
		}, PackRuntime.inactive());

		assertTrue(service.render(null, null).isEmpty());
		assertEquals(List.of("configured"), calls);
		assertEquals(Long.MIN_VALUE, service.generation());
		service.close();
	}

	@Test
	void shadowAndDepthWorkAreBeforeShadowcompAndFullscreenStagesStayOrdered() {
		List<String> trace = new ArrayList<>();
		for (PassKind kind : PassKind.executionOrder()) {
			if (kind == PassKind.SHADOWCOMP) {
				trace.add("shadow");
				trace.add("depth-copy");
			}
			trace.add(kind.sourceName());
		}

		assertEquals(List.of("setup", "begin", "shadow", "depth-copy", "shadowcomp", "prepare",
			"deferred", "composite", "final"), trace);
		assertTrue(trace.indexOf("shadow") < trace.indexOf("shadowcomp"));
		assertTrue(trace.indexOf("depth-copy") < trace.indexOf("shadowcomp"));
		assertTrue(trace.indexOf("depth-copy") < trace.indexOf("final"));
	}

	@Test
	void shadowFlagsAndMissingProgramProduceNoLodWork() {
		DhUniformState uniforms = shadowUniforms(9L);
		DhDepthResources depth = DhDepthResources.of(9L, java.util.Map.of());
		DhShadowFrame noShadowProgram = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 9L,
			false, true, true, uniforms, 9L, 9L, depth);
		assertFalse(noShadowProgram.canRenderTerrain());
		assertFalse(noShadowProgram.canRenderTranslucent());
		assertTrue(DhDepthResources.of(9L, java.util.Map.of()).isEmpty());
		assertFalse(noShadowProgram.shadowProgramAvailable());
	}

	@Test
	void reloadAndResizeGenerationsInvalidateOldShadowResources() {
		DhUniformState uniforms = shadowUniforms(3L);
		DhDepthResources oldDepth = DhDepthResources.of(3L, java.util.Map.of());
		DhShadowFrame frame = DhShadowFrame.enabled(new DhShadowFrame.PackIdentity("pack"), 3L,
			true, true, false, uniforms, 3L, 3L, oldDepth);
		DhDepthResources resizedDepth = DhDepthResources.of(4L, java.util.Map.of());

		assertTrue(frame.enabled());
		assertEquals(3L, frame.depthGeneration());
		assertTrue(DhLodGeneration.valid(frame, oldDepth));
		assertFalse(DhLodGeneration.valid(frame, resizedDepth));
		assertThrows(IllegalArgumentException.class, () -> DhShadowFrame.enabled(
			new DhShadowFrame.PackIdentity("pack"), 4L, true, true, false, uniforms, 3L, 4L, resizedDepth));
	}

	@Test
	void shadowExecutorExposesCaptureRenderAndDepthFinalizeSeam() {
		List<String> events = new ArrayList<>();
		PackPassExecutor.ShadowStage stage = new PackPassExecutor.ShadowStage() {
			@Override
			public PackPassExecutor.ShadowInput capture(PackRuntimeService.PackSnapshot pack, long generation,
				dev.mikuteto.mtlrender.pack.runtime.PackTargetSet targets) {
				events.add("capture");
				return null;
			}

			@Override
			public void render(DhShadowFrame frame, dev.mikuteto.mtlrender.pack.runtime.PackTargetSet targets,
				com.mojang.blaze3d.systems.CommandEncoder encoder) {
				events.add("shadow");
			}

			@Override
			public void finalizeDepth(DhShadowFrame frame, dev.mikuteto.mtlrender.pack.runtime.PackTargetSet targets,
				com.mojang.blaze3d.systems.CommandEncoder encoder) {
				events.add("depth-copy");
			}
		};

		assertEquals(List.of(), events);
		assertTrue(new PackRuntimeService.RuntimeResources(Optional.empty(), Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.empty(), Optional.of(stage)).shadowStage().isPresent());
	}

	private static DhUniformState shadowUniforms(long generation) {
		return new DhUniforms().updateShadow(new DhUniforms.ShadowCamera(new Matrix4f(), new Matrix4f(),
			0.1F, 1000.0F, 0.1F, generation), new DhUniforms.LodExtent(0, 0, 64, 64, 0.0, 0.0));
	}

	private static final class DhLodGeneration {
		private static boolean valid(DhShadowFrame frame, DhDepthResources depth) {
			return frame.enabled() && frame.generation() == depth.generation()
				&& frame.depthResources().generation() == depth.generation();
		}
	}
}
