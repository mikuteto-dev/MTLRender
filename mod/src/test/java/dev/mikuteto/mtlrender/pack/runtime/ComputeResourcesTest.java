package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ComputeResourcesTest {
	@Test
	void resolvesRayLikeVolumeAndScreenResources() {
		ComputeResources.TargetSize targets = new ComputeResources.TargetSize(1920, 1080, 2048, 2048);
		ComputePlan.CustomImageSpec volume = image("voxels", ComputePlan.ImageDimension.THREE,
			new ComputePlan.Dimensions(128, 96, 64), "r32ui", "r32ui", "uint", false, false, 0, 0);
		ComputePlan.CustomImageSpec history = image("history", ComputePlan.ImageDimension.TWO,
			ComputePlan.Dimensions.two(1, 1), "rgba16f", "rgba16f", "float", true, true, .5f, .25f);
		assertEquals(new ComputeResources.ResolvedDimensions(128, 96, 64), ComputeResources.resolve(volume, targets));
		assertEquals(new ComputeResources.ResolvedDimensions(960, 270, 1), ComputeResources.resolve(history, targets));
		assertEquals(GpuFormat.R32_UINT, ComputeResources.format(volume));
		assertEquals(GpuFormat.RGBA16_FLOAT, ComputeResources.format(history));
	}

	@Test
	void clearsOnlyOnCreationOrShapeChangeAndReusesUnchangedResources() {
		FakeFactory factory = new FakeFactory();
		Map<String, ComputePlan.CustomImageSpec> images = new LinkedHashMap<>();
		images.put("history", image("history", ComputePlan.ImageDimension.TWO, ComputePlan.Dimensions.two(1, 1),
			"rgba8", "rgba8", "ubyte", true, true, .5f, .5f));
		ComputeResources resources = new ComputeResources(factory, Map.of(), images, new ComputeResources.TargetSize(100, 80, 200, 200));
		ComputeResources.Resource first = factory.created.getFirst();
		assertEquals(1, factory.clears.get());
		resources.reconcile(Map.of(), images, new ComputeResources.TargetSize(100, 80, 200, 200));
		assertSame(first, factory.created.getFirst());
		assertEquals(1, factory.created.size());
		assertEquals(1, factory.clears.get());
		resources.reconcile(Map.of(), images, new ComputeResources.TargetSize(120, 80, 200, 200));
		assertEquals(2, factory.created.size());
		assertEquals(2, factory.clears.get());
		assertEquals(1, factory.closed.get());
		resources.close();
		assertEquals(2, factory.closed.get());
	}

	@Test
	void mapsAllBundledImageFormats() {
		assertEquals(GpuFormat.R16_UINT, ComputeResources.format(image("a", ComputePlan.ImageDimension.TWO,
			ComputePlan.Dimensions.two(1, 1), "r16ui", "r16ui", "uint", false, false, 0, 0)));
		assertEquals(GpuFormat.RG11B10_FLOAT, ComputeResources.format(image("b", ComputePlan.ImageDimension.TWO,
			ComputePlan.Dimensions.two(1, 1), "r11g11b10f", "r11g11b10f", "float", false, false, 0, 0)));
		assertEquals(GpuFormat.R16_FLOAT, ComputeResources.format(image("c", ComputePlan.ImageDimension.TWO,
			ComputePlan.Dimensions.two(1, 1), "r16f", "r16f", "float", false, false, 0, 0)));
		assertEquals(GpuFormat.RGBA8_UNORM, ComputeResources.format(image("d", ComputePlan.ImageDimension.TWO,
			ComputePlan.Dimensions.two(1, 1), "rgba8", "rgba8", "ubyte", false, false, 0, 0)));
	}

	private static ComputePlan.CustomImageSpec image(String name, ComputePlan.ImageDimension dimension, ComputePlan.Dimensions size,
		String format, String internal, String type, boolean clear, boolean relative, float width, float height) {
		return new ComputePlan.CustomImageSpec(name, 0, dimension, size, ComputePlan.Access.READ_WRITE,
			format, internal, type, clear, relative, width, height);
	}

	private static final class FakeFactory implements ComputeResources.Factory {
		private final java.util.List<ComputeResources.Resource> created = new java.util.ArrayList<>();
		private final AtomicInteger clears = new AtomicInteger();
		private final AtomicInteger closed = new AtomicInteger();
		@Override public ComputeResources.Resource buffer(String name, long size) { return resource(); }
		@Override public ComputeResources.Resource image(String name, GpuFormat format, ComputeResources.ResolvedDimensions dimensions) { return resource(); }
		@Override public void clear(ComputeResources.Resource resource) { clears.incrementAndGet(); }
		private ComputeResources.Resource resource() {
			ComputeResources.Resource resource = new ComputeResources.Resource(null, null, null) {
				@Override public void close() { closed.incrementAndGet(); }
			};
			created.add(resource);
			return resource;
		}
	}
}
