package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.uniform.GameState;
import dev.mikuteto.mtlrender.pack.uniform.Type;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PackUniformBindingsTest {
	@Test
	void writesReflectedValuesAtTheirOffsets() {
		Reflection reflection = new Reflection(0, false, List.of(), List.of(
			new Reflection.UniformMember("flag", 1, 0, 4, 0),
			new Reflection.UniformMember("count", 2, 4, 4, 0),
			new Reflection.UniformMember("value", 4, 8, 4, 0),
			new Reflection.UniformMember("vector", 8, 16, 16, 0),
			new Reflection.UniformMember("matrix", 16, 32, 48, 0)
		), List.of(), List.of(), null);
		GameState values = new GameState();
		values.set("flag", true);
		values.set("count", 7);
		values.set("value", 2.5);
		values.require("vector", Type.VEC4).setNumber(0, 1.0);
		for (int index = 0; index < 9; index++) values.require("matrix", Type.MAT3).setNumber(index, index + 1.0);

		ByteBuffer buffer = new PackUniformBindings(reflection).write(values).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(1, buffer.getInt(0));
		assertEquals(7, buffer.getInt(4));
		assertEquals(2.5F, buffer.getFloat(8));
		assertEquals(1.0F, buffer.getFloat(16));
		assertEquals(4.0F, buffer.getFloat(48));
		assertEquals(9.0F, buffer.getFloat(72));
	}

	@Test
	void missingReflectedValuesRemainZero() {
		Reflection reflection = new Reflection(0, false, List.of(), List.of(
			new Reflection.UniformMember("missing", 4, 0, 4, 0)
		), List.of(), List.of(), null);
		ByteBuffer buffer = new PackUniformBindings(reflection).write(new GameState());
		assertEquals(0.0F, buffer.getFloat(0));
	}
}
