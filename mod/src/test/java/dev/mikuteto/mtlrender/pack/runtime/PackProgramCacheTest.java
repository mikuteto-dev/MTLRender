package dev.mikuteto.mtlrender.pack.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class PackProgramCacheTest {
	@Test
	void cachesSuccessfulResultAndPipeline() {
		FakeState state = new FakeState(4, true);
		List<String> created = new ArrayList<>();
		PackProgramCache<String> cache = new PackProgramCache<>(state, created::add);

		PackProgramCache.ProgramResult<String> first = cache.pipeline("overworld", "terrain", (dimension, program) -> {
			created.add("created");
			return "pipeline";
		});
		PackProgramCache.ProgramResult<String> second = cache.pipeline("overworld", "terrain", (dimension, program) -> {
			created.add("unexpected");
			return "other";
		});

		assertSame(first, second);
		assertEquals(List.of("created"), created);
		assertEquals(new PackProgramCache.Identity(4, 1), cache.generation("overworld").orElseThrow().identity());
	}

	@Test
	void retiresEveryDimensionOnceBeforeNewVersionIsVisible() {
		FakeState state = new FakeState(1, true);
		List<String> closed = new ArrayList<>();
		PackProgramCache<String> cache = new PackProgramCache<>(state, closed::add);
		cache.pipeline("overworld", "terrain", (dimension, program) -> "overworld-pipeline");
		cache.pipeline("nether", "terrain", (dimension, program) -> "nether-pipeline");

		state.version = 2;
		PackProgramCache<String>.Generation generation = cache.generation("overworld").orElseThrow();

		assertEquals(List.of("overworld-pipeline", "nether-pipeline"), closed);
		assertEquals(2, generation.identity().version());
		cache.close();
		cache.close();
		assertEquals(List.of("overworld-pipeline", "nether-pipeline"), closed);
	}

	@Test
	void closesSharedPipelineHandleOnce() {
		FakeState state = new FakeState(3, true);
		List<String> closed = new ArrayList<>();
		PackProgramCache<String> cache = new PackProgramCache<>(state, closed::add);
		cache.pipeline("overworld", "terrain", (dimension, program) -> "shared");
		cache.pipeline("overworld", "water", (dimension, program) -> "shared");

		state.version = 4;
		cache.generation("overworld");

		assertEquals(List.of("shared"), closed);
	}

	@Test
	void closesSharedPipelineAcrossDimensionsOnce() {
		FakeState state = new FakeState(5, true);
		List<String> closed = new ArrayList<>();
		PackProgramCache<String> cache = new PackProgramCache<>(state, closed::add);
		cache.pipeline("overworld", "terrain", (dimension, program) -> "shared");
		cache.pipeline("nether", "terrain", (dimension, program) -> "shared");

		state.version = 6;
		cache.generation("overworld");

		assertEquals(List.of("shared"), closed);
	}

	@Test
	void isolatesDimensionsAndCachesFailures() {
		FakeState state = new FakeState(9, true);
		PackProgramCache<String> cache = new PackProgramCache<>(state, ignored -> { });
		RuntimeException failure = new RuntimeException("compile failed");
		PackProgramCache.ProgramResult<String> first = cache.result("overworld", "optional", () -> {
			throw failure;
		});
		PackProgramCache.ProgramResult<String> second = cache.result("overworld", "optional", () -> {
			throw new RuntimeException("unexpected");
		});
		PackProgramCache.ProgramResult<String> other = cache.pipeline("nether", "optional", (dimension, program) -> "nether-pipeline");

		assertSame(first, second);
		assertFalse(first.successful());
		assertSame(failure, first.failure().orElseThrow());
		assertTrue(other.successful());
		assertEquals(9, cache.generation("nether").orElseThrow().identity().version());
		assertFalse(cache.generation("overworld").orElseThrow().identity().equals(cache.generation("nether").orElseThrow().identity()));
	}

	@Test
	void renderAndComputeProgramsWithSameNameDoNotShareResult() {
		FakeState state = new FakeState(11, true);
		PackProgramCache<String> cache = new PackProgramCache<>(state, ignored -> { });
		List<String> compiled = new ArrayList<>();
		GeometryPathSelector.ProgramIdentity render = new GeometryPathSelector.ProgramIdentity(
			GeometryPathSelector.ProgramKind.RENDER, "same");
		GeometryPathSelector.ProgramIdentity compute = new GeometryPathSelector.ProgramIdentity(
			GeometryPathSelector.ProgramKind.COMPUTE, "same");

		PackProgramCache.ProgramResult<String> renderResult = cache.getOrCompile("overworld", render, () -> {
			compiled.add("render");
			return PackProgramCache.ProgramResult.success("render-pipeline");
		});
		PackProgramCache.ProgramResult<String> computeResult = cache.getOrCompile("overworld", compute, () -> {
			compiled.add("compute");
			return PackProgramCache.ProgramResult.success("compute-pipeline");
		});

		assertEquals(List.of("render", "compute"), compiled);
		assertEquals("render-pipeline", renderResult.pipeline().orElseThrow());
		assertEquals("compute-pipeline", computeResult.pipeline().orElseThrow());
	}

	@Test
	void staleGenerationCannotExposeOldPipeline() {
		FakeState state = new FakeState(12, true);
		PackProgramCache<String> cache = new PackProgramCache<>(state, ignored -> { });
		PackProgramCache.ProgramResult<String> old = cache.pipeline("overworld", "terrain", (dimension, program) -> "old");
		state.version = 13;
		cache.generation("overworld");

		assertThrows(IllegalStateException.class, old::pipeline);
	}

	@Test
	void inactiveStateDoesNotLoadOrCloseGpuPipelines() {
		FakeState state = new FakeState(0, false);
		List<String> calls = new ArrayList<>();
		PackProgramCache<String> cache = new PackProgramCache<>(state, calls::add);

		assertEquals(Optional.empty(), cache.generation("overworld"));
		assertEquals(Optional.empty(), cache.generation("overworld"));
		assertTrue(calls.isEmpty());
	}

	private static final class FakeState implements PackProgramCache.StateSource {
		private long version;
		private boolean active;

		private FakeState(long version, boolean active) {
			this.version = version;
			this.active = active;
		}

		@Override
		public PackProgramCache.State state() {
			return new PackProgramCache.State(version, active);
		}
	}
}
