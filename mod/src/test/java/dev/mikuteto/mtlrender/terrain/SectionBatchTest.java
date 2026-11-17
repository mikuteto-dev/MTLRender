package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.chunk.SectionCompiler;

class SectionBatchTest {
	private static final Object COLORS = new Object();
	private static final Object MODELS = new Object();
	private static final SectionBatch.Compatibility COMPATIBLE =
		new SectionBatch.Compatibility(3L, true, false, COLORS, MODELS);

	@Test
	void acceptsCompatibleRequestsAndRejectsDifferentInputs() {
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, Long.MAX_VALUE);
		assertTrue(scope.accept(request(1L, COMPATIBLE, true)));
		SectionBatch.Compatibility different = new SectionBatch.Compatibility(4L, true, false, COLORS, MODELS);
		assertFalse(scope.accept(request(2L, different, true)));
		assertEquals(SectionBatch.FlushReason.INCOMPATIBLE_INPUTS, scope.flushReason());
		scope.close();
	}

	@Test
	void flushesAtFixedThreshold() {
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, Long.MAX_VALUE);
		for (long key = 1L; key <= SectionBatch.MAX_ITEMS; key++) {
			assertTrue(scope.accept(request(key, COMPATIBLE, true)));
		}
		assertFalse(scope.accept(request(9L, COMPATIBLE, true)));
		assertEquals(SectionBatch.FlushReason.LIMIT_REACHED, scope.flushReason());
		scope.close();
	}

	@Test
	void flushesOnTimeout() {
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, 10L, () -> 10L);
		assertFalse(scope.accept(request(1L, COMPATIBLE, true)));
		assertEquals(SectionBatch.FlushReason.TIMEOUT, scope.flushReason());
		scope.close();
	}

	@Test
	void cancellationReleasesInstalledResults() {
		AtomicInteger releases = new AtomicInteger();
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, Long.MAX_VALUE);
		SectionBatch.Request request = request(1L, COMPATIBLE, true);
		scope.install(request, SectionBatch.Result.nativeResult(new SectionCompiler.Results(), releases::incrementAndGet));
		scope.cancel();
		assertEquals(1, releases.get());
		assertEquals(SectionBatch.FlushReason.CANCELLATION, scope.flushReason());
		scope.close();
	}

	@Test
	void takesEachResultOnlyOnce() {
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, Long.MAX_VALUE);
		SectionBatch.Request request = request(1L, COMPATIBLE, true);
		SectionCompiler.Results value = new SectionCompiler.Results();
		scope.install(request, value);
		assertSame(value, scope.take(request.key()).value());
		assertEquals(SectionBatch.Status.ONE_SECTION_FALLBACK, scope.take(request.key()).status());
		scope.close();
	}

	@Test
	void releasesUnusedAndReplacedResults() {
		AtomicInteger releases = new AtomicInteger();
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, Long.MAX_VALUE);
		SectionBatch.Request first = request(1L, COMPATIBLE, true);
		SectionBatch.Request second = request(2L, COMPATIBLE, true);
		scope.install(first, SectionBatch.Result.nativeResult(new SectionCompiler.Results(), releases::incrementAndGet));
		scope.install(first, SectionBatch.Result.nativeResult(new SectionCompiler.Results(), releases::incrementAndGet));
		scope.install(second, SectionBatch.Result.nativeResult(new SectionCompiler.Results(), releases::incrementAndGet));
		scope.close();
		assertEquals(3, releases.get());
	}

	@Test
	void bindsScopeForTaskLocalConsumption() {
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, Long.MAX_VALUE);
		assertEquals(null, SectionBatch.Scope.current());
		try (SectionBatch.Scope.Handoff ignored = scope.bind()) {
			assertSame(scope, SectionBatch.Scope.current());
		}
		assertEquals(null, SectionBatch.Scope.current());
		scope.close();
	}

	@Test
	void rejectsIneligibleDynamicContent() {
		SectionBatch.Scope scope = new SectionBatch.Scope(COMPATIBLE, Long.MAX_VALUE);
		assertFalse(scope.accept(request(1L, COMPATIBLE, false)));
		assertEquals(SectionBatch.FlushReason.INELIGIBLE_DYNAMIC_CONTENT, scope.flushReason());
		scope.close();
	}

	private static SectionBatch.Request request(long key, SectionBatch.Compatibility compatibility, boolean eligible) {
		return new SectionBatch.Request(SectionPos.of((int) key, 0, 0), null, null, null, compatibility, eligible);
	}
}
