package dev.mikuteto.mtlrender.server;

public enum ChunkLoadPhase {
	REGION_READ,
	NBT_DECODE,
	MAIN_THREAD_APPLY,
	STATUS_PROMOTION
}
