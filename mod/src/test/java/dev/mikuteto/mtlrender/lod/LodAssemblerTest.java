package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LodAssemblerTest {
	@Test
	void placesChunkAndSectionBoundaries() {
		Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> sources = sources();
		LodData.LodKey key = new LodData.LodKey("saved", 0, 0, 0, 0);
		LodData.LodSection section = LodAssembler.assembleLevel0(key, sources, Optional.empty()).orElseThrow();
		int[] states = section.voxelValues();
		assertEquals(1, states[0]);
		assertEquals(4, states[31 | 31 * 32 | 31 * 1024]);
		assertEquals(4, states[16 | 16 * 32 | 16 * 1024]);
		assertEquals(LodData.LOD_VOXELS * Integer.BYTES * 2L + LodData.LOD_VOXELS * 2L + 1024L * Integer.BYTES, section.estimatedBytes());
	}

	@Test
	void rejectsMissingInputWithoutErasingPrior() {
		Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> sources = sources();
		sources.remove(new LodData.ChunkKey("saved", 1, 1));
		LodData.LodKey key = new LodData.LodKey("saved", 0, 0, 0, 0);
		LodData.LodSection prior = new LodData.LodSection(key, new int[LodData.LOD_VOXELS], LodData.Heightmap.lod(new int[1024]));
		assertTrue(LodAssembler.assembleLevel0(key, sources, Optional.of(prior)).orElseThrow() == prior);
		assertFalse(LodAssembler.assembleLevel0(key, sources, Optional.empty()).isPresent());
	}

	@Test
	void reducesRepresentativeLightsAndHeightmaps() {
		Map<LodData.LodKey, LodData.LodSection> children = new HashMap<>();
		LodData.LodKey key = new LodData.LodKey("saved", 1, 0, 0, 0);
		for (int z = 0; z < 2; z++) {
			for (int y = 0; y < 2; y++) {
				for (int x = 0; x < 2; x++) {
					int state = x == 1 && y == 1 && z == 1 ? 9 : 1;
					int[] states = new int[LodData.LOD_VOXELS];
					byte[] sky = new byte[LodData.LOD_VOXELS];
					byte[] block = new byte[LodData.LOD_VOXELS];
					states[0] = state;
					sky[0] = (byte) (x + y + z);
					block[0] = (byte) (8 - x - y - z);
					int[] heights = new int[1024];
					java.util.Arrays.fill(heights, x + y + z);
					LodData.LodKey child = new LodData.LodKey("saved", 0, x * 32, y * 32, z * 32);
					children.put(child, new LodData.LodSection(child, states, new int[LodData.LOD_VOXELS], sky, block, LodData.Heightmap.lod(heights)));
				}
			}
		}
		LodData.LodSection result = LodAssembler.reduce(key, children, state -> state == 9 ? 15 : state == 1 ? 1 : 0, Optional.empty()).orElseThrow();
		assertEquals(9, result.voxelValues()[0]);
		assertEquals(3, Byte.toUnsignedInt(result.skyLight()[0]));
		assertEquals(5, Byte.toUnsignedInt(result.blockLight()[0]));
		assertEquals(3, result.heightmap().values()[0]);
		assertEquals(0, result.key().originX());
	}

	private static Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> sources() {
		Map<LodData.ChunkKey, Map<Integer, LodData.DecodedSection>> result = new HashMap<>();
		for (int z = 0; z < 2; z++) {
			for (int x = 0; x < 2; x++) {
				LodData.ChunkKey chunk = new LodData.ChunkKey("saved", x, z);
				Map<Integer, LodData.DecodedSection> sections = new HashMap<>();
				for (int y = 0; y < 2; y++) {
					int[] states = new int[LodData.SOURCE_VOXELS];
					java.util.Arrays.fill(states, 1 + x + z + y);
					sections.put(y, new LodData.DecodedSection(chunk, y, states, new int[LodData.SOURCE_VOXELS], new byte[LodData.SOURCE_VOXELS], new byte[LodData.SOURCE_VOXELS], LodData.Heightmap.chunk(new int[256])));
				}
				result.put(chunk, sections);
			}
		}
		return result;
	}
}
