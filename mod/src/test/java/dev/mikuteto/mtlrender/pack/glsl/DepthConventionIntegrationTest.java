package dev.mikuteto.mtlrender.pack.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.depth.DepthConversion;
import dev.mikuteto.mtlrender.pack.depth.PackDepthProducer;
import dev.mikuteto.mtlrender.pack.uniform.DepthConvention;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class DepthConventionIntegrationTest {
	private static final float EPSILON = 1.0E-5F;

	@Test
	void keepsProjectionAndClipBoundariesInThePackConvention() {
		Matrix4f game = new Matrix4f(
			1.2F, 0.1F, 0.2F, 0.3F,
			0.0F, 1.7F, -0.4F, 0.5F,
			0.2F, -0.3F, -2.1F, -1.0F,
			0.7F, 0.8F, -2.6F, 0.4F);
		Matrix4f pack = DepthConvention.gameProjectionToPack(game);
		Matrix4f restored = DepthConvention.packProjectionToGame(pack);
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				assertEquals(game.get(column, row), restored.get(column, row), EPSILON);
			}
		}
		assertEquals(-1.0F, DepthConvention.gameNdcZToPack(1.0F), EPSILON);
		assertEquals(1.0F, DepthConvention.gameNdcZToPack(0.0F), EPSILON);
		assertEquals(0.0F, DepthConvention.packClipZToGame(DepthConvention.gameClipZToPack(0.0F, 2.0F), 2.0F), EPSILON);
	}

	@Test
	void convertsDepthTexturesOnceAndLeavesIntermediateYUnchanged() {
		assertEquals(1.0F, DepthConversion.gameToPack(0.0F), EPSILON);
		assertEquals(0.0F, DepthConversion.gameToPack(1.0F), EPSILON);
		assertEquals(1.0F, DepthConvention.packWindowDepthToGame(0.0F), EPSILON);
		assertEquals(0.0F, DepthConvention.packWindowDepthToGame(1.0F), EPSILON);
		PackDepthProducer<String> producer = new PackDepthProducer<>();
		AtomicInteger conversions = new AtomicInteger();
		assertTrue(producer.produce("depthtex0", 4L, "game", source -> {
			conversions.incrementAndGet();
			return "pack-" + source;
		}).isPresent());
		assertTrue(producer.produce("gdepth", 4L, "game", source -> {
			conversions.incrementAndGet();
			return "wrong-" + source;
		}).isPresent());
		assertEquals(1, conversions.get());
		assertEquals("pack-game", producer.read("gdepthtex").orElseThrow());
		Matrix4f projection = new Matrix4f().identity();
		Matrix4f converted = DepthConvention.gameProjectionToPack(projection);
		assertEquals(projection.m10(), converted.m10(), EPSILON);
		assertEquals(projection.m11(), converted.m11(), EPSILON);
	}

	@Test
	void mapsPackDepthComparisonsWithoutChangingTheDefaultDescriptor() {
		assertEquals(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN,
			PackPipelineDesc.gameCompare(com.mojang.blaze3d.platform.CompareOp.LESS_THAN));
		assertEquals(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL,
			PackPipelineDesc.gameCompare(com.mojang.blaze3d.platform.CompareOp.LESS_THAN_OR_EQUAL));
		assertEquals(com.mojang.blaze3d.platform.CompareOp.LESS_THAN,
			PackPipelineDesc.gameCompare(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN));
		assertEquals(com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS,
			PackPipelineDesc.gameCompare(com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS));
	}
}
