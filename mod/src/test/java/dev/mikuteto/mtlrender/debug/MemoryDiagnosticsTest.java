package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MemoryDiagnosticsTest {
	@Test
	void accountingReleaseIsIdempotentAndGenerationSafe() {
		MemoryAccount account = new MemoryAccount();
		MemoryAccount.Allocation old = account.retain(MemoryAccount.Subsystem.TERRAIN, 128, 2);
		assertEquals(128, account.snapshot().bytes());
		old.close();
		old.close();
		assertEquals(0, account.snapshot().bytes());

		MemoryAccount.Allocation stale = account.retain(MemoryAccount.Subsystem.GUI, 64, 1);
		account.reset();
		MemoryAccount.Allocation current = account.retain(MemoryAccount.Subsystem.GUI, 32, 1);
		stale.release();
		assertEquals(32, account.snapshot().bytes());
		current.release();
		assertEquals(0, account.snapshot().bytes());
	}

	@Test
	void providerFailureDoesNotHideOtherProviders() {
		MemoryDiagnostics diagnostics = new MemoryDiagnostics(new MemoryAccount(), () -> true, 0);
		try (MemoryDiagnostics.Registration bad = diagnostics.register("bad", () -> {
			throw new IllegalStateException("failure");
		}); MemoryDiagnostics.Registration good = diagnostics.register("good", () -> new MemorySource.Values(
			OptionalLong.of(7), OptionalLong.of(8), OptionalLong.of(9), OptionalLong.of(10)))) {
			MemoryReport report = diagnostics.snapshot();
			assertEquals(7, report.nativeAllocationBytes().getAsLong());
			assertEquals(8, report.currentAllocatedSize().getAsLong());
			assertTrue(report.sources().get(0).failed());
			assertFalse(report.sources().get(1).failed());
		}
	}

	@Test
	void subsystemOrderingIsFixed() {
		MemoryAccount account = new MemoryAccount();
		account.retain(MemoryAccount.Subsystem.GUI, 3, 1);
		List<MemoryAccount.Subsystem> actual = account.snapshot().entries().stream().map(MemoryAccount.Entry::subsystem).toList();
		assertEquals(List.of(MemoryAccount.Subsystem.values()), actual);
	}

	@Test
	void disabledPathDoesNotSampleProviders() {
		AtomicInteger calls = new AtomicInteger();
		MemoryDiagnostics diagnostics = new MemoryDiagnostics(new MemoryAccount(), () -> false, 0);
		diagnostics.register("source", () -> {
			calls.incrementAndGet();
			return MemorySource.Values.unavailable();
		});
		assertNull(diagnostics.snapshot());
		assertTrue(diagnostics.diagnosticLines().isEmpty());
		assertEquals("", diagnostics.text());
		assertEquals(0, calls.get());
	}

	@Test
	void unavailableValuesAreFormattedExplicitly() {
		MemoryDiagnostics diagnostics = new MemoryDiagnostics(new MemoryAccount(), () -> true, 0);
		try (MemoryDiagnostics.Registration registration = diagnostics.register("metal", MemorySource.Values::unavailable)) {
			String text = diagnostics.text();
			assertTrue(text.contains("native.native_allocation_bytes: unavailable"));
			assertTrue(text.contains("native.currentAllocatedSize: unavailable"));
			assertTrue(text.contains("native.phys_footprint: unavailable"));
		}
	}

	@Test
	void reportFormattingIncludesAllContractFields() {
		MemoryAccount account = new MemoryAccount();
		account.retain(MemoryAccount.Subsystem.SHADERS, 42, 2);
		MemoryDiagnostics diagnostics = new MemoryDiagnostics(account, () -> true, 0);
		try (MemoryDiagnostics.Registration registration = diagnostics.register("metal", () -> new MemorySource.Values(
			OptionalLong.of(11), OptionalLong.of(12), OptionalLong.of(13), OptionalLong.of(14)))) {
			String text = diagnostics.text();
			assertTrue(text.contains("jvm_heap.used_bytes:"));
			assertTrue(text.contains("tracked_java.shaders.bytes: 42"));
			assertTrue(text.contains("native.native_allocation_bytes: 11"));
			assertTrue(text.contains("native.currentAllocatedSize: 12"));
			assertTrue(text.contains("native.recommendedMaxWorkingSetSize: 13"));
			assertTrue(text.contains("native.phys_footprint: 14"));
		}
	}
}
