package dev.mikuteto.mtlrender.client;

import java.util.Arrays;

public final class ChunkLoadCoordinator {
	public interface Listener {
		void onChunkPacket(long chunkKey, long generation);

		void onLightReady(long sectionKey, long generation);

		void onCompileAdmission(long sectionKey, long generation);
	}

	private static final byte CHUNK_PACKET = 1;
	private static final byte LIGHT_READY = 2;
	private static final byte COMPILE_ADMISSION = 3;
	private static final int CHUNK_SEEN = 1;
	private static final int LIGHT_SEEN = 1;
	private static final int COMPILE_SEEN = 2;

	private final long[] eventKeys;
	private final long[] eventGenerations;
	private final byte[] eventKinds;
	private final long[] chunkKeys;
	private final long[] chunkGenerations;
	private final int[] chunkStates;
	private final long[] sectionKeys;
	private final long[] sectionGenerations;
	private final int[] sectionStates;
	private final int mask;
	private final int eventMask;
	private long generation = 1;
	private int eventHead;
	private int eventTail;
	private int eventCount;

	public ChunkLoadCoordinator() {
		this(4096);
	}

	public ChunkLoadCoordinator(int capacity) {
		if (capacity < 1 || capacity > 1 << 29 || Integer.bitCount(capacity) != 1) {
			throw new IllegalArgumentException("capacity must be a positive power of two");
		}
		this.eventKeys = new long[capacity];
		this.eventGenerations = new long[capacity];
		this.eventKinds = new byte[capacity];
		int tableCapacity = capacity << 1 <= 0 ? 1 << 30 : capacity << 1;
		this.chunkKeys = new long[tableCapacity];
		this.chunkGenerations = new long[tableCapacity];
		this.chunkStates = new int[tableCapacity];
		this.sectionKeys = new long[tableCapacity];
		this.sectionGenerations = new long[tableCapacity];
		this.sectionStates = new int[tableCapacity];
		this.mask = tableCapacity - 1;
		this.eventMask = capacity - 1;
	}

	public long generation() {
		return this.generation;
	}

	public boolean recordChunkPacket(long chunkKey, long token) {
		if (token != this.generation) {
			return false;
		}
		int slot = find(this.chunkKeys, this.chunkGenerations, chunkKey, token);
		if (slot < 0 || (this.chunkStates[slot] & CHUNK_SEEN) != 0 || !offer(CHUNK_PACKET, chunkKey, token)) {
			return false;
		}
		this.chunkStates[slot] |= CHUNK_SEEN;
		return true;
	}

	public boolean notifyLightReady(long sectionKey, long token) {
		if (token != this.generation) {
			return false;
		}
		int slot = find(this.sectionKeys, this.sectionGenerations, sectionKey, token);
		if (slot < 0 || (this.sectionStates[slot] & LIGHT_SEEN) != 0) {
			return false;
		}
		if (!offer(LIGHT_READY, sectionKey, token)) {
			return false;
		}
		this.sectionStates[slot] |= LIGHT_SEEN;
		return true;
	}

	public boolean notifyCompileAdmission(long sectionKey, long token) {
		if (token != this.generation) {
			return false;
		}
		int slot = find(this.sectionKeys, this.sectionGenerations, sectionKey, token);
		if (slot < 0 || (this.sectionStates[slot] & COMPILE_SEEN) != 0) {
			return false;
		}
		if (!offer(COMPILE_ADMISSION, sectionKey, token)) {
			return false;
		}
		this.sectionStates[slot] |= COMPILE_SEEN;
		return true;
	}

	public int drain(int limit, Listener listener) {
		if (limit < 0) {
			throw new IllegalArgumentException("limit must not be negative");
		}
		if (listener == null) {
			throw new NullPointerException("listener");
		}
		int drained = 0;
		while (drained < limit && this.eventCount != 0) {
			int index = this.eventHead;
			byte kind = this.eventKinds[index];
			long key = this.eventKeys[index];
			long token = this.eventGenerations[index];
			this.eventHead = this.eventHead + 1 & this.eventMask;
			this.eventCount--;
			drained++;
			if (kind == CHUNK_PACKET) {
				listener.onChunkPacket(key, token);
			} else if (kind == LIGHT_READY) {
				listener.onLightReady(key, token);
			} else {
				listener.onCompileAdmission(key, token);
			}
		}
		return drained;
	}

	public int pending() {
		return this.eventCount;
	}

	public void reset() {
		this.eventHead = 0;
		this.eventTail = 0;
		this.eventCount = 0;
		Arrays.fill(this.chunkStates, 0);
		Arrays.fill(this.chunkGenerations, 0);
		Arrays.fill(this.sectionStates, 0);
		Arrays.fill(this.sectionGenerations, 0);
		this.generation++;
		if (this.generation == 0) {
			this.generation = 1;
		}
	}

	private boolean offer(byte kind, long key, long token) {
		if (this.eventCount == this.eventKinds.length) {
			return false;
		}
		int index = this.eventTail;
		this.eventKinds[index] = kind;
		this.eventKeys[index] = key;
		this.eventGenerations[index] = token;
		this.eventTail = this.eventTail + 1 & this.eventMask;
		this.eventCount++;
		return true;
	}

	private int find(long[] keys, long[] generations, long key, long token) {
		int slot = mix(key) & this.mask;
		int start = slot;
		while (this.generationsAt(generations, slot) != token || keys[slot] != key) {
			if (this.generationsAt(generations, slot) == 0) {
				keys[slot] = key;
				generations[slot] = token;
				return slot;
			}
			slot = slot + 1 & this.mask;
			if (slot == start) {
				return -1;
			}
		}
		return slot;
	}

	private long generationsAt(long[] generations, int slot) {
		return generations[slot];
	}

	private static int mix(long value) {
		long mixed = value * -7046029254386353131L;
		mixed ^= mixed >>> 32;
		return (int)mixed;
	}
}
