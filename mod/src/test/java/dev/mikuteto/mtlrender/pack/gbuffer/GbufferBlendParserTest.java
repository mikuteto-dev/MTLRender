package dev.mikuteto.mtlrender.pack.gbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import org.junit.jupiter.api.Test;

final class GbufferBlendParserTest {
	@Test
	void acceptsEveryFactor() {
		for (BlendFactor factor : BlendFactor.values()) {
			assertTrue(GbufferBlendParser.parse(factor.name() + " ONE ONE ZERO").isPresent(), factor.name());
		}
	}

	@Test
	void acceptsEveryOperation() {
		for (BlendOp operation : BlendOp.values()) {
			String value = "SRC_ALPHA ONE " + operation.name() + " ONE ZERO " + operation.name();
			assertEquals(operation, GbufferBlendParser.parse(value).orElseThrow().color().op());
			assertEquals(operation, GbufferBlendParser.parse(value).orElseThrow().alpha().op());
		}
	}

	@Test
	void acceptsIrisFourFactorSyntaxAndOff() {
		assertTrue(GbufferBlendParser.parse("SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA").isPresent());
		assertTrue(GbufferBlendParser.isOff("off"));
		assertTrue(GbufferBlendParser.isOff("false"));
	}

	@Test
	void rejectsInvalidSyntax() {
		assertTrue(GbufferBlendParser.parse("SRC_ALPHA UNKNOWN ONE ZERO").isEmpty());
		assertTrue(GbufferBlendParser.parse("SRC_ALPHA ONE").isEmpty());
		assertTrue(GbufferBlendParser.parse("SRC_ALPHA ONE ADD ONE ZERO").isEmpty());
	}
}
