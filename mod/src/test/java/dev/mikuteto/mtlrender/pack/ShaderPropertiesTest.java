package dev.mikuteto.mtlrender.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.mikuteto.mtlrender.pack.runtime.ComputePlan;

class ShaderPropertiesTest {
	@Test
	void parsesIrisLogicalLinesAndPropertySeparators() {
		ShaderProperties properties = new ShaderProperties("screen = \\\r\n"
				+ "  first second <empty>\r\nsliders = A B\r\n# ignored\r\n", "");

		assertEquals(java.util.List.of("first", "second", "<empty>"), properties.screen());
		assertEquals(java.util.List.of("A", "B"), properties.sliders());
		assertEquals("value", ShaderProperties.parse("key : value\r\n").get("key"));
	}

	@Test
	void parsesDispatchOverridesAndIndirectCommands() {
		ShaderProperties properties = new ShaderProperties("", "workGroups=4 5 6\nworkGroupsRender=2 3\nindirect.composite=7 16");

		assertEquals(new ComputePlan.Absolute(new ComputePlan.Dimensions(4, 5, 6)), properties.workGroups().orElseThrow());
		assertEquals(new ComputePlan.RenderRelative(2, 3), properties.workGroupsRender().orElseThrow());
		assertEquals(new ComputePlan.Indirect("bufferObject.7", 16), properties.indirectDispatches().get("composite"));
	}

	@Test
	void parsesAbsoluteRelativeAndNamedBuffers() {
		ShaderProperties properties = new ShaderProperties("", "bufferObject.0=64 named\nbufferObject.1=128 true 0.5 0.25");

		ComputePlan.SsboSpec absolute = properties.ssbos().get(0);
		assertEquals("named", absolute.name());
		assertEquals(64, absolute.sizeBytes());
		assertFalse(absolute.relative());
		ComputePlan.SsboSpec relative = properties.ssbos().get(1);
		assertTrue(relative.relative());
		assertEquals(0.5f, relative.scaleX());
		assertEquals(0.25f, relative.scaleY());
	}

	@Test
	void parsesImageDimensionsFormatsAndClear() {
		ShaderProperties properties = new ShaderProperties("", "image.one=none rgba8 rgba8 uint true false 32\n"
				+ "image.two=img rgba16f rgba16f float false false 64 32\n"
				+ "image.three=img rgba32f rgba32f float true false 8 4 2\n"
				+ "image.relative=img r32ui r32ui uint false true 0.5 0.25");

		assertEquals(ComputePlan.ImageDimension.ONE, properties.computeImages().get("one").dimension());
		assertEquals(32, properties.computeImages().get("one").size().width());
		assertTrue(properties.computeImages().get("one").clear());
		assertEquals("rgba16f", properties.computeImages().get("two").format());
		assertEquals(2, properties.computeImages().get("three").size().depth());
		assertTrue(properties.computeImages().get("relative").relative());
	}

	@Test
	void malformedValuesRemainRawAndDuplicateEffectiveValueWins() {
		ShaderProperties properties = new ShaderProperties("", "image.bad=img rgba8 rgba8 uint maybe false 4\n"
				+ "bufferObject.2=bad\nimage.same=img rgba8 rgba8 uint false false 4\n"
				+ "image.same=img rgba16 rgba16 uint true false 8");

		assertTrue(properties.raw().containsKey("image.bad"));
		assertTrue(properties.raw().containsKey("bufferObject.2"));
		assertEquals("rgba16", properties.computeImages().get("same").format());
		assertInstanceOf(ComputePlan.CustomImageSpec.class, properties.computeImages().get("same"));
	}
}
