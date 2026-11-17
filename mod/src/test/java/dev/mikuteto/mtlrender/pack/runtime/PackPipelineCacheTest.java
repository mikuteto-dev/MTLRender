package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackPipelineCacheTest {
	@Test
	void diskTranslationHitSkipsTranslatorAndCreatesPackDescriptor() throws Exception {
		PackCacheKey key = key(1);
		PackProgramRequest request = request("composite");
		PackTranslationArtifact translation = translation("cached");
		var root = Files.createTempDirectory("pack-pipeline-cache");
		PackTranslationDiskCache disk = new PackTranslationDiskCache(root);
		disk.write(key, translation);
		AtomicInteger translated = new AtomicInteger();
		AtomicInteger created = new AtomicInteger();
		PackPipelineCache cache = cache(disk, (ignored, unused) -> {
			translated.incrementAndGet();
			return translation("fresh");
		}, descriptor -> {
			created.incrementAndGet();
			assertEquals("cached", descriptor.vertexMsl());
			return 7;
		});

		PackPipelineCache.Result result = cache.resolve(new PackPipelineCache.Request(request, key,
			PassKind.COMPOSITE, constants()));

		assertTrue(result.successful());
		assertEquals(0, translated.get());
		assertEquals(1, created.get());
		assertEquals(7, result.artifact().orElseThrow().runtime().renderPipeline());
	}

	@Test
	void pipelineIdentityIncludesStateAndTranslationKey() throws Exception {
		AtomicInteger created = new AtomicInteger();
		PackPipelineCache cache = cache(new PackTranslationDiskCache(Files.createTempDirectory("pack-pipeline-cache")),
			(ignored, key) -> translation("msl-" + key.hashCode()), ignored -> created.incrementAndGet());
		PackProgramRequest request = request("composite");
		PackPipelineCache.Request first = new PackPipelineCache.Request(request, key(1), PassKind.COMPOSITE, constants());
		PackPipelineCache.Request second = new PackPipelineCache.Request(request, key(2), PassKind.COMPOSITE, constants());

		PackPipelineCache.Result a = cache.resolve(first);
		PackPipelineCache.Result b = cache.resolve(first);
		PackPipelineCache.Result c = cache.resolve(second);

		assertSame(a, b);
		assertFalse(a.artifact().orElseThrow().runtime().renderPipeline() == c.artifact().orElseThrow().runtime().renderPipeline());
		assertEquals(2, created.get());
	}

	@Test
	void failureIsCachedAndDoesNotPoisonAnotherProgram() throws Exception {
		AtomicInteger created = new AtomicInteger();
		PackPipelineCache cache = cache(new PackTranslationDiskCache(Files.createTempDirectory("pack-pipeline-cache")),
			(ignored, unused) -> translation("msl"), ignored -> {
				if (created.incrementAndGet() == 1) throw new IllegalStateException("archive failure");
				return 9;
			});
		PackPipelineCache.Request failed = new PackPipelineCache.Request(request("bad"), key(1), PassKind.COMPOSITE, constants());
		PackPipelineCache.Request good = new PackPipelineCache.Request(request("good"), key(2), PassKind.COMPOSITE, constants());

		PackPipelineCache.Result first = cache.resolve(failed);
		PackPipelineCache.Result second = cache.resolve(failed);
		PackPipelineCache.Result other = cache.resolve(good);

		assertSame(first, second);
		assertFalse(first.successful());
		assertTrue(other.successful());
		assertEquals(2, created.get());
	}

	@Test
	void retirementClosesEachHandleOnceAndNoPackDoesNoWork() throws Exception {
		FakeState state = new FakeState(1, false);
		AtomicInteger translated = new AtomicInteger();
		AtomicInteger created = new AtomicInteger();
		AtomicInteger closed = new AtomicInteger();
		PackPipelineCache cache = cache(state, new PackTranslationDiskCache(Files.createTempDirectory("pack-pipeline-cache")),
			(ignored, unused) -> { translated.incrementAndGet(); return translation("msl"); }, ignored -> {
			created.incrementAndGet(); return 11;
		}, ignored -> closed.incrementAndGet());
		assertFalse(cache.generation().isPresent());
		cache.resolve(new PackPipelineCache.Request(request("none"), key(1), PassKind.COMPOSITE, constants()));
		assertEquals(0, translated.get());
		assertEquals(0, created.get());

		state.active = true;
		cache.resolve(new PackPipelineCache.Request(request("one"), key(1), PassKind.COMPOSITE, constants()));
		cache.resolve(new PackPipelineCache.Request(request("two"), key(2), PassKind.COMPOSITE, constants()));
		state.version = 2;
		cache.generation();
		cache.close();
		assertEquals(1, closed.get());
	}

	private static PackPipelineCache cache(PackTranslationDiskCache disk, PackPipelineCache.Translator translator,
		PackPipelineCache.PipelineFactory factory) {
		return cache(new FakeState(1, true), disk, translator, factory, ignored -> { });
	}

	private static PackPipelineCache cache(FakeState state, PackTranslationDiskCache disk,
		PackPipelineCache.Translator translator, PackPipelineCache.PipelineFactory factory,
		PackPipelineCache.PipelineCloser closer) {
		return new PackPipelineCache(state, disk, translator, (request, artifact, pass, constants) ->
			new PackPipelineDesc(artifact.vertexMsl(), artifact.vertexEntry(), artifact.fragmentMsl(), artifact.fragmentEntry(),
				List.of(), null, List.of(), null, null, false, PackPipelineDesc.CullMode.NONE), factory, closer);
	}

	private static PackProgramRequest request(String name) {
		return new PackProgramRequest("pack", "world", name,
			Map.of(Program.Stage.VERTEX, "v", Program.Stage.FRAGMENT, "f"));
	}

	private static PackTranslationArtifact translation(String value) {
		return new PackTranslationArtifact(value, "vertex", value, "fragment",
			new PackTranslationArtifact.Reflection(List.of(), List.of(), List.of(), null));
	}

	private static PackFunctionConstants constants() {
		return PackFunctionConstants.genericFallback(4);
	}

	private static PackCacheKey key(int value) {
		return new PackCacheKey(1, 1, 1, new byte[] {(byte) value}, Map.of(), Map.of(), "mod", "os", "gpu");
	}

	private static final class FakeState implements PackPipelineCache.StateSource {
		private long version;
		private boolean active;

		private FakeState(long version, boolean active) {
			this.version = version;
			this.active = active;
		}

		@Override
		public PackPipelineCache.State state() {
			return new PackPipelineCache.State(version, active);
		}
	}
}
