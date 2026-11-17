package dev.mikuteto.mtlrender.pack.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackRuntimeLifecycleTest {
	@Test
	void unconfiguredSourceDoesNotReadManagerState() {
		List<String> calls = new java.util.ArrayList<>();
		PackRuntimeService.PackSource source = new PackRuntimeService.PackSource() {
			@Override
			public boolean configured() {
				calls.add("config");
				return false;
			}

			@Override
			public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() {
				calls.add("active");
				throw new AssertionError("active pack was read");
			}

			@Override
			public long version() {
				calls.add("version");
				throw new AssertionError("pack version was read");
			}
		};

		assertTrue(source.snapshot().isEmpty());
		assertEquals(List.of("config"), calls);
	}

	@Test
	void closeWithoutAnActiveGenerationIsIdempotent() {
		PackRuntimeService service = new PackRuntimeService(
			new PackRuntimeService.PackSource() {
				@Override
				public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() {
					return Optional.empty();
				}

				@Override
				public long version() {
					return 0L;
				}
			}, PackRuntime.inactive());

		service.close();
		service.close();
		assertEquals(Optional.empty(), service.runtime());
	}
}
