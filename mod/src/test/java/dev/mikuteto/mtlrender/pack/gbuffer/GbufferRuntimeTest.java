package dev.mikuteto.mtlrender.pack.gbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class GbufferRuntimeTest {
	@Test
	void frameSnapshotCannotMixGenerations() {
		List<String> events = new ArrayList<>();
		GbufferRuntime runtime = new GbufferRuntime((generation, transition) -> events.add("retire-" + generation.identity().pack()));
		GbufferRuntime.Generation old = generation("old", 1L, 10L, 20L, 30L, 40L);
		GbufferRuntime.Generation next = generation("next", 2L, 11L, 21L, 31L, 41L);

		runtime.publish(Optional.of(old));
		GbufferRuntime.FrameSnapshot frame = runtime.beginFrame();
		runtime.publish(Optional.of(next));

		GbufferRuntime.Identity identity = frame.generation().orElseThrow().identity();
		assertEquals("old", identity.pack());
		assertEquals(10L, identity.targetPlanGeneration());
		assertEquals(20L, identity.pipelineSourceGeneration());
		assertEquals(30L, identity.alphaBlendGeneration());
		assertEquals(40L, identity.drawUniformGeneration());
		runtime.endFrame(frame);
		assertEquals("next", runtime.published().orElseThrow().identity().pack());
	}

	@Test
	void failedActivationDoesNotInstallOrLeaveAStaleHook() {
		FakeProviders oldProviders = new FakeProviders();
		FakeProviders failedProviders = new FakeProviders();
		failedProviders.failure = true;
		GbufferRuntime runtime = new GbufferRuntime((generation, transition) -> { });
		runtime.publish(Optional.of(new GbufferRuntime.Generation(identity("old"), oldProviders)));
		assertThrows(IllegalStateException.class,
			() -> runtime.publish(Optional.of(new GbufferRuntime.Generation(identity("failed"), failedProviders))));
		assertFalse(runtime.published().isPresent());
		assertEquals(1, oldProviders.removes);
		assertEquals(1, failedProviders.removes);
	}

	@Test
	void closeIsIdempotentAndRetiresOnce() {
		FakeProviders providers = new FakeProviders();
		List<String> retired = new ArrayList<>();
		GbufferRuntime runtime = new GbufferRuntime((generation, transition) -> retired.add(generation.identity().pack()));
		runtime.publish(Optional.of(new GbufferRuntime.Generation(identity("pack"), providers)));
		runtime.close();
		runtime.close();

		assertEquals(1, providers.removes);
		assertEquals(List.of("pack"), retired);
		assertThrows(IllegalStateException.class, runtime::beginFrame);
	}

	@Test
	void noPackSnapshotIsAbsentWithoutCallingProviders() {
		FakeProviders providers = new FakeProviders();
		GbufferRuntime runtime = new GbufferRuntime((generation, transition) -> { });
		GbufferRuntime.FrameSnapshot frame = runtime.beginFrame();

		assertTrue(frame.generation().isEmpty());
		assertEquals(0, providers.installs);
		assertEquals(0, providers.removes);
		runtime.endFrame(frame);
	}

	private static GbufferRuntime.Generation generation(String pack, long version, long target, long source,
		long alpha, long uniforms) {
		return new GbufferRuntime.Generation(new GbufferRuntime.Identity(pack, version, "overworld", target, source, alpha, uniforms),
			new FakeProviders());
	}

	private static GbufferRuntime.Identity identity(String pack) {
		return new GbufferRuntime.Identity(pack, 1L, "overworld", 2L, 3L, 4L, 5L);
	}

	private static final class FakeProviders implements GbufferRuntime.Providers {
		private int installs;
		private int removes;
		private boolean failure;

		@Override
		public void install() {
			installs++;
			if (failure) throw new IllegalStateException("activation failed");
		}

		@Override
		public void remove() {
			removes++;
		}
	}
}
