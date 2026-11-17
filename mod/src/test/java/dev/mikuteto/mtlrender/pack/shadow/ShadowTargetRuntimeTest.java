package dev.mikuteto.mtlrender.pack.shadow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowTargetRuntimeTest {
	@Test
	void publishesGenerationsAndRetiresOnlyAfterCompletion() throws IOException {
		AtomicLong submitted = new AtomicLong();
		AtomicLong completed = new AtomicLong();
		java.util.List<FakeTargets> created = new java.util.ArrayList<>();
		ShadowTargetRuntime runtime = new ShadowTargetRuntime(plan -> {
			FakeTargets targets = new FakeTargets(plan.generation());
			created.add(targets);
			return targets;
		}, submitted::get, completed::get);

		FakeTargets first = (FakeTargets) runtime.publish(plan(1));
		assertEquals(1L, first.generation());
		submitted.set(4);
		FakeTargets second = (FakeTargets) runtime.publish(plan(2));
		assertFalse(first.closed);
		completed.set(4);
		runtime.collect(completed.get());
		assertFalse(first.closed);
		completed.set(5);
		runtime.collect(completed.get());
		assertTrue(first.closed);
		assertEquals(0, runtime.retiredCount());
		runtime.close();
		assertTrue(second.closed);
		runtime.close();
		assertEquals(2, created.size());
	}

	@Test
	void rejectsWrongGenerationAndRepeatedClose() throws IOException {
		AtomicLong completed = new AtomicLong(Long.MAX_VALUE);
		ShadowTargetRuntime runtime = new ShadowTargetRuntime(plan -> new FakeTargets(9), () -> 0L, completed::get);
		assertThrows(IllegalArgumentException.class, () -> runtime.publish(plan(3)));
		runtime.close();
		runtime.close();
		assertTrue(runtime.current().isEmpty());
	}

	@Test
	void noPackHasNoResourceAllocation() {
		AtomicLong allocations = new AtomicLong();
		ShadowTargetRuntime runtime = new ShadowTargetRuntime(plan -> {
			allocations.incrementAndGet();
			return new FakeTargets(plan.generation());
		}, () -> 0L, () -> 0L);
		assertTrue(runtime.current().isEmpty());
		assertEquals(0, allocations.get());
	}

	private static ShadowTargetPlan plan(long generation) throws IOException {
		Path root = Path.of(System.getProperty("user.dir"), "run", "shaderpacks");
		if (!Files.isDirectory(root)) root = Path.of(System.getProperty("user.dir"), "mod", "run", "shaderpacks");
		try (var paths = Files.list(root)) {
			Path pack = paths.filter(path -> path.getFileName().toString().contains("ComplementaryReimagined")).findFirst().orElseThrow();
			return ShadowTargetPlan.build(dev.mikuteto.mtlrender.pack.ShaderPack.load(pack, Map.of()), generation);
		}
	}

	private static final class FakeTargets implements ShadowTargetRuntime.TargetSet {
		private final long generation;
		private boolean closed;

		private FakeTargets(long generation) {
			this.generation = generation;
		}

		@Override
		public long generation() {
			return generation;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
