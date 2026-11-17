package dev.mikuteto.mtlrender.pack.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;

import org.junit.jupiter.api.Test;

final class ComputeRuntimeServiceIntegrationTest {
	@Test
	void emptySelectorDoesNotCreateOrQueryComputeGeneration() {
		Counters counters = new Counters();
		PackRuntimeService.PackSource source = new PackRuntimeService.PackSource() {
			@Override
			public boolean configured() {
				counters.configured++;
				return false;
			}

			@Override
			public Optional<dev.mikuteto.mtlrender.pack.ShaderPack> active() {
				counters.active++;
				return Optional.empty();
			}

			@Override
			public long version() {
				counters.version++;
				return 1L;
			}
		};
		PackRuntimeService service = new PackRuntimeService(source, (pack, width, height) -> {
			counters.factory++;
			return new PackRuntimeService.RuntimeBundle(PackRuntime.inactive(), PackRuntimeService.RuntimeResources.empty(),
				new ComputeRuntime(throwingFactory(), (access, concurrent) -> counters.compute++));
		});

		assertFalse(service.render(null, null).isPresent());
		assertEquals(1, counters.configured);
		assertEquals(0, counters.active);
		assertEquals(0, counters.version);
		assertEquals(0, counters.factory);
		assertEquals(0, counters.compute);
		service.close();
	}

	private static ComputeRuntime.Factory throwingFactory() {
		return new ComputeRuntime.Factory() {
			@Override
			public ComputePipelineSet pipelines() {
				throw new IllegalStateException("compute factory must not be queried");
			}

			@Override
			public ComputeResources resources(java.util.Map<Integer, ComputePlan.SsboSpec> ssbos,
				java.util.Map<String, ComputePlan.CustomImageSpec> images, ComputeResources.TargetSize targets) {
				throw new IllegalStateException("compute resources must not be queried");
			}

			@Override
			public ComputeTextureBindings textures(java.util.List<dev.mikuteto.mtlrender.pack.texture.TextureSpec> textures) {
				throw new IllegalStateException("compute textures must not be queried");
			}
		};
	}

	private static final class Counters {
		private int configured;
		private int active;
		private int version;
		private int factory;
		private int compute;
	}
}
