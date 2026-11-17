package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PackTerrainLayoutTest {
	@Test
	void fixesVersionOffsetsStrideAndAlignment() {
		assertEquals(1, PackTerrainLayout.VERSION);
		assertEquals(16, PackTerrainLayout.RECORD_STRIDE);
		assertEquals(0, PackTerrainLayout.BLOCK_ID_OFFSET);
		assertEquals(4, PackTerrainLayout.MID_TEX_COORD_OFFSET);
		assertEquals(8, PackTerrainLayout.NORMAL_TANGENT_OFFSET);
		assertEquals(12, PackTerrainLayout.MID_BLOCK_OFFSET);
		assertEquals(48, PackTerrainLayout.bytesFor(3));
		assertTrue(PackTerrainLayout.isAligned(0));
		assertTrue(PackTerrainLayout.isAligned(8));
		assertFalse(PackTerrainLayout.isAligned(2));
	}

	@Test
	void acceptsEveryFieldMaskCombinationOnlyWhenPresent() {
		for (int required = 0; required <= PackTerrainLayout.ALL_FIELDS; required++) {
			if (!PackTerrainLayout.isValidFieldMask(required)) continue;
			for (int present = 0; present <= PackTerrainLayout.ALL_FIELDS; present++) {
				if (!PackTerrainLayout.isValidFieldMask(present)) continue;
				assertEquals((required & ~present) == 0,
					PackTerrainLayout.isCompatible(required, present));
			}
		}
	}

	@Test
	void rejectsTruncatedAndMalformedRecords() {
		assertTrue(PackTerrainLayout.hasRecords(0, 0));
		assertTrue(PackTerrainLayout.hasRecords(2, 32));
		assertFalse(PackTerrainLayout.hasRecords(2, 31));
		assertFalse(PackTerrainLayout.hasRecords(2, 33));
		assertFalse(PackTerrainLayout.hasRecords(-1, 0));
		assertThrows(IllegalArgumentException.class,
			() -> PackTerrainLayout.validate(2, 0, 0, 1, 16, 0));
		assertThrows(IllegalArgumentException.class,
			() -> PackTerrainLayout.validate(1, 16, PackTerrainLayout.ALL_FIELDS, 1, 16, 0));
		assertThrows(IllegalArgumentException.class,
			() -> PackTerrainLayout.validate(1, 0, 0, 1, 15, 0));
		assertThrows(IllegalArgumentException.class,
			() -> PackTerrainLayout.validate(1, 0, 0, 1, 16, 2));
	}

	@Test
	void identifiesVanillaExtraAndNoPackWithoutWork() {
		PackTerrainRequirements none = PackTerrainRequirements.none();
		assertEquals(PackTerrainRequirements.Mode.NONE, none.mode());
		assertTrue(none.isNoWork());
		assertEquals(0, none.sidecarMask());
		assertEquals(0, none.inputs().size());

		PackTerrainRequirements vanilla = PackTerrainRequirements.fromDeclaredInputs(true,
			List.of("Position", "Color", "UV0", "UV2"), null);
		assertEquals(PackTerrainRequirements.Mode.VANILLA, vanilla.mode());
		assertFalse(vanilla.needsExtraData());
		assertEquals(0, vanilla.sidecarMask());

		PackTerrainRequirements extra = PackTerrainRequirements.fromDeclaredInputs(true,
			List.of("mtlr_entity", "mtlr_mid_texcoord", "mtlr_normal", "mtlr_tangent", "mtlr_mid_block"), null);
		assertEquals(PackTerrainRequirements.Mode.EXTRA, extra.mode());
		assertEquals(PackTerrainLayout.ALL_FIELDS, extra.sidecarMask());
		assertTrue(extra.needsExtraData());
		assertEquals("terrain mesher", PackTerrainLayout.producer());
		assertEquals("pack terrain vertex stage", PackTerrainLayout.consumer());
		assertTrue(PackTerrainLayout.invalidationRule().contains("ordinary terrain mesh remains usable"));
	}

	@Test
	void inactivePackDoesNotInspectInputsOrAllocateMetadata() {
		PackTerrainRequirements requirements = PackTerrainRequirements.fromDeclaredInputs(false,
			List.of("mc_Entity"), null);
		assertEquals(PackTerrainRequirements.none(), requirements);
		assertTrue(requirements.isNoWork());
	}
}
