package dev.mikuteto.mtlrender.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.junit.jupiter.api.Test;

class WeatherColumnStreamTest {
	@Test
	void preservesVanillaColumnFieldsForBothMaterials() {
		WeatherEffectRenderer.ColumnInstance rain = new WeatherEffectRenderer.ColumnInstance(3, -7, 12, 28, 0.0F, -4.25F, 0x00F000F0);
		WeatherEffectRenderer.ColumnInstance snow = new WeatherEffectRenderer.ColumnInstance(-5, 9, 4, 31, 0.375F, -0.125F, 0x00D000D0);
		WeatherColumnStream stream = new WeatherColumnStream();

		stream.append(rain, false);
		stream.append(snow, true);

		assertEquals(2, stream.count());
		assertEquals(1, stream.rainCount());
		assertEquals(1, stream.snowCount());
		ByteBuffer buffer = stream.buffer();
		assertEquals(WeatherColumnStream.STRIDE * 2, buffer.remaining());
		assertColumn(buffer, 0, rain, 0);
		assertColumn(buffer, WeatherColumnStream.STRIDE, snow, 1);
	}

	private static void assertColumn(ByteBuffer buffer, int offset, WeatherEffectRenderer.ColumnInstance expected, int material) {
		assertEquals(expected.x(), buffer.getInt(offset));
		assertEquals(expected.z(), buffer.getInt(offset + 4));
		assertEquals(expected.bottomY(), buffer.getInt(offset + 8));
		assertEquals(expected.topY(), buffer.getInt(offset + 12));
		assertEquals(expected.uOffset(), buffer.getFloat(offset + 16));
		assertEquals(expected.vOffset(), buffer.getFloat(offset + 20));
		assertEquals(expected.lightCoords(), buffer.getInt(offset + 24));
		assertEquals(material, buffer.getInt(offset + 28));
	}
}
