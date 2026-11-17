package dev.mikuteto.mtlrender.pack.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

final class UniformWriterTest {
	@Test
	void writesStd140ScalarVectorAndMatrixLayouts() {
		GameState values = new GameState();
		values.set("scalar", 2.5);
		values.set("vector", 1.0, 2.0, 3.0);
		Value matrix = values.require("matrix", Type.MAT3);
		for (int i = 0; i < 9; i++) {
			matrix.setNumber(i, i + 1.0);
		}
		ByteBuffer buffer = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
		new UniformWriter().write(buffer, List.of(
			new UniformWriter.Member("scalar", Type.FLOAT, 0),
			new UniformWriter.Member("vector", Type.VEC3, 16),
			new UniformWriter.Member("matrix", Type.MAT3, 32),
			new UniformWriter.Member("missing", Type.INT, 80)
		), values);
		assertEquals(2.5F, buffer.getFloat(0));
		assertEquals(1.0F, buffer.getFloat(16));
		assertEquals(3.0F, buffer.getFloat(24));
		assertEquals(1.0F, buffer.getFloat(32));
		assertEquals(4.0F, buffer.getFloat(48));
		assertEquals(9.0F, buffer.getFloat(72));
		assertEquals(0, buffer.getInt(80));
	}
}
