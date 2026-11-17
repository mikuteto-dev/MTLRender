package dev.mikuteto.mtlrender.pack.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TessellationRuntimeIntegrationTest {
	@Test
	void absentPackDoesNotInvokeOptionalTessellationDelegate() {
		AtomicInteger calls = new AtomicInteger();
		PackRuntimeService.TessellationDelegate delegate = request -> {
			calls.incrementAndGet();
			throw new AssertionError("tessellation delegate invoked without a pack");
		};
		PackRuntimeService.RuntimeResources resources = new PackRuntimeService.RuntimeResources(
			Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(delegate));
		assertTrue(resources.tessellation().isPresent());
		PackRuntimeService service = new PackRuntimeService(new PackRuntimeService.PackSource() {
			@Override public boolean configured() { return false; }
			@Override public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() { return Optional.empty(); }
			@Override public long version() { return 1L; }
		}, PackRuntime.inactive(), resources);
		assertTrue(service.render(null, null).isEmpty());
		assertEquals(0, calls.get());
		service.close();
	}

	@Test
	void emptyResourcesKeepTessellationOptional() {
		assertFalse(PackRuntimeService.RuntimeResources.empty().tessellation().isPresent());
	}
}
