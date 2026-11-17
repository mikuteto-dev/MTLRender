package dev.mikuteto.mtlrender.terrain;

public final class PackTerrainUnits {
	public static final int LEGACY_UNIT_STRIDE = TerrainPreparation.UNIT_STRIDE;
	public static final int PACK_UNIT_STRIDE = TerrainPreparation.PACK_UNIT_STRIDE;
	public static final int LEGACY_RECORD_STRIDE = TerrainPreparation.RECORD_STRIDE;
	public static final int PACK_RECORD_STRIDE = TerrainPreparation.PACK_RECORD_STRIDE;
	public static final int LEGACY_RECORD_UPDATE_STRIDE = TerrainPreparation.RECORD_UPDATE_STRIDE;
	public static final int PACK_RECORD_UPDATE_STRIDE = TerrainPreparation.PACK_RECORD_UPDATE_STRIDE;
	public static final int METADATA_STRIDE = PackTerrainLayout.RECORD_STRIDE;
	public static final int PACK_OPTION = 1 << 1;
	public static final int PACK_FLAG = 1 << 6;

	private PackTerrainUnits() {
	}

	public static int recordBytes(boolean pack) {
		return pack ? PACK_RECORD_STRIDE : LEGACY_RECORD_STRIDE;
	}

	public static int unitBytes(boolean pack) {
		return pack ? PACK_UNIT_STRIDE : LEGACY_UNIT_STRIDE;
	}

	public static boolean validMetadata(long address, int stride, int quads) {
		return address >= 0 && stride == METADATA_STRIDE && PackTerrainLayout.isAligned(address)
			&& quads >= 0 && PackTerrainLayout.hasRecords(quads, (long) stride * quads);
	}
}
