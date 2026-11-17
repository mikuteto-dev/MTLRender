package dev.mikuteto.mtlrender.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.mikuteto.mtlrender.particle.ParticleInstanceLayout.ParticleInstance;
import org.junit.jupiter.api.Test;

class ParticleInstanceLayoutTest {
	@Test
	void roundTripsAllFieldsAtFiniteExtremes() {
		ParticleInstance source = new ParticleInstance(
			Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_NORMAL,
			-1.0F, 0.0F, 1.0F, -Float.MIN_VALUE,
			Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_NORMAL, 1.0F, -1.0F,
			0x80FF00AA, 0xF1234567
		);
		ByteBuffer bytes = ByteBuffer.allocate(ParticleInstanceLayout.STRIDE).order(ByteOrder.nativeOrder());
		ParticleInstanceLayout.pack(bytes, 0, source);

		assertEquals(source, ParticleInstanceLayout.unpack(bytes, 0));
	}

	@Test
	void streamUsesOneRecordPerParticleAndGrowsBoundedly() {
		ParticleInstance instance = new ParticleInstance(1.0F, 2.0F, 3.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 7, 8);
		ParticleInstanceStream stream = new ParticleInstanceStream();
		for (int index = 0; index < 1025; index++) {
			stream.add(instance);
		}

		assertEquals(1025, stream.count());
		assertEquals(instance, ParticleInstanceLayout.unpack(stream.data(), 1024 * ParticleInstanceLayout.STRIDE));
	}
}
