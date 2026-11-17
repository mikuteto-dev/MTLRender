package dev.mikuteto.mtlrender.server;

import dev.mikuteto.mtlrender.debug.Benchmark;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.ChunkPos;

public final class ChunkLoadTrace {
	private static final AtomicReference<Sink> SINK = new AtomicReference<>();
	private static final Object SINK_LOCK = new Object();

	private ChunkLoadTrace() {
	}

	public static boolean enabled() {
		return Benchmark.enabled() || Boolean.getBoolean("mtlrender.debug.chunkTiming");
	}

	public static void setSink(Sink sink) {
		SINK.set(Objects.requireNonNull(sink, "sink"));
	}

	public static void clearSink() {
		SINK.set(null);
	}

	public static Scope begin(ChunkPos pos, ChunkLoadPhase phase) {
		if (!enabled()) {
			return Scope.NOOP;
		}
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(phase, "phase");
		return new Scope(pos.pack(), phase, System.nanoTime());
	}

	public static Scope begin(ChunkLoadPhase phase) {
		if (!enabled()) {
			return Scope.NOOP;
		}
		Objects.requireNonNull(phase, "phase");
		return new Scope(0L, phase, System.nanoTime());
	}

	public static void record(ChunkPos pos, ChunkLoadPhase phase, long durationNanos) {
		if (!enabled()) {
			return;
		}
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(phase, "phase");
		dispatch(new Event(pos.pack(), phase, Math.max(0L, durationNanos)));
	}

	private static void dispatch(Event event) {
		Sink sink = SINK.get();
		if (sink == null) {
			return;
		}
		synchronized (SINK_LOCK) {
			Sink current = SINK.get();
			if (current != null) {
				current.accept(event);
			}
		}
	}

	@FunctionalInterface
	public interface Sink {
		void accept(Event event);
	}

	public record Event(long chunkKey, ChunkLoadPhase phase, long durationNanos) {
		public ChunkPos chunkPos() {
			return ChunkPos.unpack(chunkKey);
		}
	}

	public static final class Scope implements AutoCloseable {
		private static final Scope NOOP = new Scope();
		private final long chunkKey;
		private final ChunkLoadPhase phase;
		private final long startedNanos;
		private final AtomicBoolean closed;

		private Scope() {
			chunkKey = 0L;
			phase = null;
			startedNanos = 0L;
			closed = new AtomicBoolean(true);
		}

		private Scope(long chunkKey, ChunkLoadPhase phase, long startedNanos) {
			this.chunkKey = chunkKey;
			this.phase = phase;
			this.startedNanos = startedNanos;
			this.closed = new AtomicBoolean();
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				dispatch(new Event(chunkKey, phase, Math.max(0L, System.nanoTime() - startedNanos)));
			}
		}
	}
}
