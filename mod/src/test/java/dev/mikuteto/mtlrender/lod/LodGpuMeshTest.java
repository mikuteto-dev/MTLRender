package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LodGpuMeshTest {
	private static final LodData.LodKey KEY = new LodData.LodKey("minecraft:overworld", 0, 0, 0, 0);

	@Test
	void validatesVertexAndFaceMetadata() {
		LodMesh mesh = mesh(96, 64, 32, new int[] {1, 0, 0, 0, 0, 0, 0});
		LodGpuMesh.Metadata metadata = LodGpuMesh.validate(mesh);
		assertEquals(64, metadata.quadOffset());
		assertEquals(32, metadata.quadBytes());
	}

	@Test
	void rejectsFaceCountMismatch() {
		LodMesh mesh = mesh(96, 64, 32, new int[] {0, 0, 0, 0, 0, 0, 0});
		assertThrows(IllegalArgumentException.class, () -> LodGpuMesh.validate(mesh));
	}

	@Test
	void rejectsMetadataOutsideBuffer() {
		LodMesh mesh = mesh(80, 64, 32, new int[] {1, 0, 0, 0, 0, 0, 0});
		assertThrows(IllegalArgumentException.class, () -> LodGpuMesh.validate(mesh));
	}

	private static LodMesh mesh(int bytes, int quadOffset, int quadBytes, int[] faces) {
		return LodMesh.nativeResult(KEY, quadBytes, faces, 1, new byte[bytes]);
	}
}
