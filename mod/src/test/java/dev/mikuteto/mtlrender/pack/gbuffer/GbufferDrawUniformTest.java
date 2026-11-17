package dev.mikuteto.mtlrender.pack.gbuffer;

import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.ids.IdMap;
import dev.mikuteto.mtlrender.pack.runtime.PackUniformState;
import dev.mikuteto.mtlrender.pack.uniform.Type;
import dev.mikuteto.mtlrender.pack.uniform.UniformWriter;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GbufferDrawUniformTest {
	@Test
	void defaultsAreDeterministicZero() {
		PackUniformState uniforms = new PackUniformState(ShaderProperties.empty());
		ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		new GbufferUniformBindings(GbufferPipelineSpec.InputProfile.Position,
			List.of(new UniformWriter.Member("entityId", Type.INT, 0),
				new UniformWriter.Member("renderStage", Type.INT, 4))).write(buffer, GbufferDrawState.zero(), uniforms);
		assertEquals(0, buffer.getInt(0));
		assertEquals(0, buffer.getInt(4));
	}

	@Test
	void writesDrawFieldsAndAllCatalogStages() {
		PackUniformState uniforms = new PackUniformState(ShaderProperties.empty());
		List<UniformWriter.Member> members = List.of(
			new UniformWriter.Member("entityId", Type.INT, 0),
			new UniformWriter.Member("blockEntityId", Type.INT, 4),
			new UniformWriter.Member("renderStage", Type.INT, 8),
			new UniformWriter.Member("currentRenderedItemId", Type.INT, 12));
		for (GbufferStage stage : GbufferStage.values()) {
			ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
			GbufferDrawState draw = new GbufferDrawState(2, 3, stage.ordinal(), 4,
				Optional.empty(), Optional.empty(), Optional.empty());
			new GbufferUniformBindings(GbufferPipelineSpec.InputProfile.Position, members).write(buffer, draw, uniforms);
			assertEquals(2, buffer.getInt(0));
			assertEquals(3, buffer.getInt(4));
			assertEquals(stage.ordinal(), buffer.getInt(8));
			assertEquals(4, buffer.getInt(12));
		}
	}

	@Test
	void resolvesMaterialIdsAndProfileAttributes() {
		IdMap.Resolved resolved = IdMap.resolve(
			new IdMap.Parsed(List.of(), List.of(new IdMap.Entry(9, List.of("minecraft:stick"), Map.of(), false)),
				List.of(new IdMap.Entry(7, List.of("minecraft:zombie"), Map.of(), false)), List.of()),
			List.of(), List.of("minecraft:stick"), List.of("minecraft:zombie"), Map.of());
		IdMap idMap = IdMap.fromResolved(resolved);
		GbufferDrawState draw = GbufferDrawState.fromRegistryIds(0, 5, GbufferStage.Entities, 0, idMap,
			Optional.of(new GbufferDrawState.Attribute(1, 2, 3, 4)), Optional.empty(), Optional.of(new GbufferDrawState.Attribute(5, 6, 7, 8)));
		assertEquals(7, draw.entityId());
		assertEquals(9, draw.currentRenderedItemId());
		assertEquals(GbufferStage.Entities.ordinal(), draw.renderStage());
		assertTrue(draw.tangent().isPresent());
		assertFalse(draw.midBlock().isPresent());
	}

	@Test
	void packsBoolIntVectorMatrixAndClearsRepeatedWrites() {
		PackUniformState uniforms = new PackUniformState(ShaderProperties.empty());
		List<UniformWriter.Member> members = List.of(
			new UniformWriter.Member("entityId", Type.INT, 0),
			new UniformWriter.Member("flag", Type.BOOL, 16),
			new UniformWriter.Member("at_tangent", Type.VEC4, 32),
			new UniformWriter.Member("matrix", Type.MAT2, 48));
		GbufferUniformBindings bindings = new GbufferUniformBindings(GbufferPipelineSpec.InputProfile.Entity, members);
		ByteBuffer buffer = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
		GbufferDrawState draw = new GbufferDrawState(8, 0, 1, 0, Optional.empty(), Optional.empty(),
			Optional.of(new GbufferDrawState.Attribute(1, 2, 3, 4)));
		uniforms.gameState().set("flag", true);
		var matrix = uniforms.gameState().require("matrix", Type.MAT2);
		matrix.setNumber(0, 1);
		matrix.setNumber(1, 2);
		matrix.setNumber(2, 3);
		matrix.setNumber(3, 4);
		bindings.write(buffer, draw, uniforms);
		assertEquals(8, buffer.getInt(0));
		assertEquals(1, buffer.getInt(16));
		assertEquals(2.0F, buffer.getFloat(36));
		assertEquals(1.0F, buffer.getFloat(48));
		assertEquals(3.0F, buffer.getFloat(64));
		buffer.putInt(0, 99).putFloat(36, 99.0F);
		bindings.write(buffer, GbufferDrawState.zero(), uniforms);
		assertEquals(0, buffer.getInt(0));
		assertEquals(0.0F, buffer.getFloat(36));
	}
}
