package dev.mikuteto.mtlrender.pack.dh;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DhDepthResourcesTest {
	@Test
	void mapsIrisNamesToTheExpectedDepthViews() {
		GpuTextureView first = new FakeView();
		GpuTextureView second = new FakeView();
		DhDepthResources resources = DhDepthResources.of(Map.of(
			"dhDepthTex0", resource(first),
			"dhDepthTex1", resource(second)));

		assertEquals(first, resources.resource("dhDepthTex").orElseThrow().view());
		assertEquals(first, resources.resource("dhDepthTex0").orElseThrow().view());
		assertEquals(second, resources.resource("dhDepthTex1").orElseThrow().view());
		assertEquals(DhDepthResources.SamplerPolicy.NEAREST_CLAMP_NO_MIP_NO_COMPARE,
			resources.resource("dhDepthTex1").orElseThrow().samplerPolicy());
	}

	@Test
	void absentOptionalResourceIsStableAndDoesNotInventAView() {
		GpuTextureView first = new FakeView();
		DhDepthResources resources = DhDepthResources.of(Map.of("dhDepthTex0", resource(first)));

		assertEquals(first, resources.resource("dhDepthTex").orElseThrow().view());
		assertTrue(resources.resource("dhDepthTex1").isEmpty());
		assertEquals(2, resources.resources().size());
		assertFalse(resources.isEmpty());
	}

	@Test
	void rejectsNamesOutsideIrisDhContract() {
		assertThrows(IllegalArgumentException.class, () -> DhDepthResources.of(Map.of("depthtex0", resource(new FakeView()))));
		assertThrows(IllegalArgumentException.class, () -> DhDepthResources.of(Map.of("dhDepthTex2", resource(new FakeView()))));
		assertThrows(IllegalArgumentException.class, () -> DhDepthResources.of(Map.of("depthtex0", resource(new FakeView()))).resource("depthtex0"));
	}

	private static DhDepthResources.Resource resource(GpuTextureView view) {
		return new DhDepthResources.Resource(view, DhDepthResources.SamplerPolicy.NEAREST_CLAMP_NO_MIP_NO_COMPARE);
	}

	private static final class FakeView extends GpuTextureView {
		private FakeView() {
			super(null, 0, 1);
		}

		@Override public void close() { }
		@Override public boolean isClosed() { return false; }
		@Override public int getWidth(int mipLevel) { return 1; }
		@Override public int getHeight(int mipLevel) { return 1; }
	}
}
