package dev.mikuteto.mtlrender.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MemoryAccount {
	public enum Subsystem {
		TERRAIN("terrain"),
		TEXTURES("textures"),
		SHADERS("shaders"),
		ENTITIES("entities"),
		PARTICLES("particles"),
		GUI("gui"),
		OTHER("other");

		private final String id;

		Subsystem(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}
	}

	public record Entry(Subsystem subsystem, long bytes, long count) {
		public Entry {
			Objects.requireNonNull(subsystem, "subsystem");
			if (bytes < 0 || count < 0) {
				throw new IllegalArgumentException("memory values must be non-negative");
			}
		}
	}

	public record Snapshot(List<Entry> entries, long bytes, long count) {
		public Snapshot {
			entries = List.copyOf(entries);
			if (bytes < 0 || count < 0) {
				throw new IllegalArgumentException("memory values must be non-negative");
			}
		}

		public Entry entry(Subsystem subsystem) {
			return entries.get(subsystem.ordinal());
		}
	}

	private static final MemoryAccount GLOBAL = new MemoryAccount();

	private final Object lock = new Object();
	private final long[] bytes = new long[Subsystem.values().length];
	private final long[] counts = new long[Subsystem.values().length];
	private long generation;

	public static MemoryAccount global() {
		return GLOBAL;
	}

	public Allocation retain(Subsystem subsystem, long bytes, long count) {
		Objects.requireNonNull(subsystem, "subsystem");
		validate(bytes, count);
		synchronized (lock) {
			int index = subsystem.ordinal();
			this.bytes[index] = add(this.bytes[index], bytes);
			this.counts[index] = add(this.counts[index], count);
			return new Allocation(this, subsystem, bytes, count, generation);
		}
	}

	public Allocation add(Subsystem subsystem, long bytes, long count) {
		return retain(subsystem, bytes, count);
	}

	public Snapshot snapshot() {
		synchronized (lock) {
			List<Entry> entries = new ArrayList<>(Subsystem.values().length);
			long totalBytes = 0;
			long totalCount = 0;
			for (Subsystem subsystem : Subsystem.values()) {
				int index = subsystem.ordinal();
				long entryBytes = bytes[index];
				long entryCount = counts[index];
				entries.add(new Entry(subsystem, entryBytes, entryCount));
				totalBytes = add(totalBytes, entryBytes);
				totalCount = add(totalCount, entryCount);
			}
			return new Snapshot(entries, totalBytes, totalCount);
		}
	}

	public void reset() {
		synchronized (lock) {
			java.util.Arrays.fill(bytes, 0L);
			java.util.Arrays.fill(counts, 0L);
			generation++;
		}
	}

	private static void validate(long bytes, long count) {
		if (bytes < 0 || count < 0) {
			throw new IllegalArgumentException("memory values must be non-negative");
		}
	}

	private static long add(long current, long value) {
		if (Long.MAX_VALUE - current < value) {
			throw new IllegalArgumentException("memory accounting overflow");
		}
		return current + value;
	}

	private void release(Allocation allocation) {
		synchronized (lock) {
			if (allocation.released) {
				return;
			}
			allocation.released = true;
			if (allocation.generation != generation) {
				return;
			}
			int index = allocation.subsystem.ordinal();
			if (bytes[index] < allocation.bytes || counts[index] < allocation.count) {
				return;
			}
			bytes[index] -= allocation.bytes;
			counts[index] -= allocation.count;
		}
	}

	public static final class Allocation implements AutoCloseable {
		private final MemoryAccount account;
		private final Subsystem subsystem;
		private final long bytes;
		private final long count;
		private final long generation;
		private boolean released;

		private Allocation(MemoryAccount account, Subsystem subsystem, long bytes, long count, long generation) {
			this.account = account;
			this.subsystem = subsystem;
			this.bytes = bytes;
			this.count = count;
			this.generation = generation;
		}

		public Subsystem subsystem() {
			return subsystem;
		}

		public long bytes() {
			return bytes;
		}

		public long count() {
			return count;
		}

		public void release() {
			account.release(this);
		}

		@Override
		public void close() {
			release();
		}
	}
}
