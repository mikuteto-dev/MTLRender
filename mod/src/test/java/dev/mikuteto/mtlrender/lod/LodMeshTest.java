package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

final class LodMeshTest {
	@Test
	void encodesCompleteAirSectionWithResolvedMaterial() {
		LodData.LodKey key = new LodData.LodKey("overworld", 0, 0, 0, 0);
		LodData.LodSection section = new LodData.LodSection(key, new int[LodData.LOD_VOXELS], LodData.Heightmap.lod(new int[LodData.LOD_SIZE * LodData.LOD_SIZE]));
		LodMesh.Input input = new LodMesh.Input(section, new LodMesh.Material[] { new LodMesh.Material(0, 7, 0, 0, 0, 0) }, true);
		ByteBuffer encoded = LodMesh.encode(input).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(LodMesh.INPUT_MAGIC, encoded.getInt(0));
		assertEquals(LodMesh.INPUT_VERSION, encoded.getInt(4));
		assertEquals(LodData.LOD_VOXELS, encoded.getInt(32));
		assertEquals(1, encoded.getInt(36));
		assertEquals(encoded.capacity(), encoded.getInt(48));
	}

	@Test
	void rejectsIncompleteSection() {
		LodData.LodKey key = new LodData.LodKey("overworld", 0, 0, 0, 0);
		LodData.LodSection section = new LodData.LodSection(key, new int[LodData.LOD_VOXELS], LodData.Heightmap.lod(new int[LodData.LOD_SIZE * LodData.LOD_SIZE]));
		assertThrows(IllegalArgumentException.class, () -> LodMesh.encode(new LodMesh.Input(section, new LodMesh.Material[0], false)));
	}
}
