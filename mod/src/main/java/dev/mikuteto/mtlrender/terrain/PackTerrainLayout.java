package dev.mikuteto.mtlrender.terrain;

public final class PackTerrainLayout {
	public static final int VERSION = 1;
	public static final int RECORD_ALIGNMENT = 4;
	public static final int RECORD_STRIDE = 16;
	public static final int BLOCK_ID_OFFSET = 0;
	public static final int MID_TEX_COORD_OFFSET = 4;
	public static final int NORMAL_TANGENT_OFFSET = 8;
	public static final int MID_BLOCK_OFFSET = 12;

	public enum Field {
		BLOCK_ID(1 << 0),
		MID_TEX_COORD(1 << 1),
		NORMAL_TANGENT(1 << 2),
		MID_BLOCK(1 << 3);

		private final int bit;

		Field(int bit) {
			this.bit = bit;
		}

		public int bit() {
			return bit;
		}
	}

	public static final int ALL_FIELDS = Field.BLOCK_ID.bit() | Field.MID_TEX_COORD.bit()
		| Field.NORMAL_TANGENT.bit() | Field.MID_BLOCK.bit();

	private PackTerrainLayout() {
	}

	public static int bytesFor(int quadCount) {
		if (quadCount < 0) {
			throw new IllegalArgumentException("negative quad count");
		}
		return Math.multiplyExact(quadCount, RECORD_STRIDE);
	}

	public static boolean isAligned(long address) {
		return address >= 0 && address % RECORD_ALIGNMENT == 0;
	}

	public static boolean isValidVersion(int version) {
		return version == VERSION;
	}

	public static boolean isValidFieldMask(int mask) {
		return (mask & ~ALL_FIELDS) == 0;
	}

	public static boolean isCompatible(int requiredMask, int presentMask) {
		return isValidFieldMask(requiredMask) && isValidFieldMask(presentMask)
			&& (requiredMask & ~presentMask) == 0;
	}

	public static boolean hasRecords(int quadCount, long byteCount) {
		return quadCount >= 0 && byteCount >= 0 && byteCount == (long) quadCount * RECORD_STRIDE;
	}

	public static void validate(int version, int requiredMask, int presentMask, int quadCount,
		long byteCount, long address) {
		if (!isValidVersion(version)) {
			throw new IllegalArgumentException("unsupported pack terrain layout version");
		}
		if (!isCompatible(requiredMask, presentMask)) {
			throw new IllegalArgumentException("incompatible pack terrain field mask");
		}
		if (!hasRecords(quadCount, byteCount)) {
			throw new IllegalArgumentException("invalid pack terrain record count or size");
		}
		if (!isAligned(address)) {
			throw new IllegalArgumentException("misaligned pack terrain records");
		}
	}

	public static String producer() {
		return "terrain mesher";
	}

	public static String consumer() {
		return "pack terrain vertex stage";
	}

	public static String invalidationRule() {
		return "pack generation or requirements change invalidates pack metadata; ordinary terrain mesh remains usable";
	}
}
