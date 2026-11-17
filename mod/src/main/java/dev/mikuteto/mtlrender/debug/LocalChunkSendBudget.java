package dev.mikuteto.mtlrender.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketProcessor;

public final class LocalChunkSendBudget {
	public static final long CHUNK_HANDLING_BUDGET_NANOS = 2_000_000L;

	private static final long NO_SAMPLE = -1L;
	private static boolean drainActive;
	private static boolean shouldYield;
	private static long drainNanos;
	private static long sampleStart = NO_SAMPLE;
	private static ClientPacketListener listener;

	private LocalChunkSendBudget() {
	}

	public static synchronized boolean isLocalClient(PacketProcessor processor) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || processor == null || minecraft.packetProcessor() != processor) {
			return false;
		}
		ClientPacketListener current = minecraft.getConnection();
		return current != null && current.isAcceptingMessages() && current.getConnection().isMemoryConnection();
	}

	public static synchronized void beginDrain(PacketProcessor processor) {
		ClientPacketListener current = currentLocalListener(processor);
		if (current == null) {
			clear();
			return;
		}
		drainActive = true;
		shouldYield = false;
		drainNanos = 0L;
		sampleStart = NO_SAMPLE;
		listener = current;
	}

	public static synchronized void endDrain() {
		clear();
	}

	public static synchronized long beginChunkSample(ClientPacketListener current) {
		if (!drainActive || current != listener || !current.isAcceptingMessages()) {
			return NO_SAMPLE;
		}
		sampleStart = System.nanoTime();
		return sampleStart;
	}

	public static synchronized void endChunkSample(long startedAtNanos) {
		endChunkSampleAt(startedAtNanos, System.nanoTime());
	}

	public static synchronized boolean shouldYield() {
		if (drainActive && listener != null && !listener.isAcceptingMessages()) {
			clear();
		}
		return drainActive && shouldYield;
	}

	static synchronized void beginDrainForTest(boolean local) {
		if (!local) {
			clear();
			return;
		}
		drainActive = true;
		shouldYield = false;
		drainNanos = 0L;
		sampleStart = NO_SAMPLE;
		listener = null;
	}

	static synchronized long beginChunkSampleForTest() {
		if (!drainActive) {
			return NO_SAMPLE;
		}
		sampleStart = 1L;
		return sampleStart;
	}

	static synchronized void endChunkSampleAtForTest(long elapsedNanos) {
		endChunkSampleAt(sampleStart, sampleStart + Math.max(0L, elapsedNanos));
	}

	private static ClientPacketListener currentLocalListener(PacketProcessor processor) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || processor == null || minecraft.packetProcessor() != processor) {
			return null;
		}
		ClientPacketListener current = minecraft.getConnection();
		if (current == null || !current.isAcceptingMessages() || !current.getConnection().isMemoryConnection()) {
			return null;
		}
		return current;
	}

	private static void endChunkSampleAt(long startedAtNanos, long endedAtNanos) {
		if (!drainActive || startedAtNanos == NO_SAMPLE || startedAtNanos != sampleStart) {
			sampleStart = NO_SAMPLE;
			return;
		}
		long elapsed = Math.max(0L, endedAtNanos - startedAtNanos);
		drainNanos = Math.min(CHUNK_HANDLING_BUDGET_NANOS, drainNanos + elapsed);
		shouldYield = drainNanos >= CHUNK_HANDLING_BUDGET_NANOS;
		sampleStart = NO_SAMPLE;
	}

	private static void clear() {
		drainActive = false;
		shouldYield = false;
		drainNanos = 0L;
		sampleStart = NO_SAMPLE;
		listener = null;
	}
}
