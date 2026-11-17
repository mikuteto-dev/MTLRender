package dev.mikuteto.mtlrender.chunk;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Timespan;

public final class ChunkLoadMarkers {
	private static final EventType EVENT_TYPE = EventType.getEventType(ChunkLoadEvent.class);
	private static final Marker DISABLED = new Marker(null, false);

	private ChunkLoadMarkers() {
	}

	public static Marker begin(Phase phase, int itemCount) {
		Objects.requireNonNull(phase, "phase");
		if (!EVENT_TYPE.isEnabled()) {
			return DISABLED;
		}
		ChunkLoadEvent event = new ChunkLoadEvent();
		event.phase = phase.name();
		event.phaseCode = phase.code;
		event.itemCount = itemCount;
		event.callerThread = Thread.currentThread().getName();
		event.begin();
		return new Marker(event, true);
	}

	public static <T> CompletableFuture<T> observe(
		Phase phase,
		int itemCount,
		CompletableFuture<T> future
	) {
		Objects.requireNonNull(future, "future");
		Marker marker = begin(phase, itemCount);
		if (!marker.isEnabled()) {
			return future;
		}
		try {
			return future.whenComplete((value, failure) -> marker.finish(failure));
		} catch (Throwable failure) {
			marker.finish(failure);
			throw failure;
		}
	}

	public enum Phase {
		RANGE_FAN_IN(1),
		SERVER_PREFETCH(2);

		private final int code;

		Phase(int code) {
			this.code = code;
		}
	}

	public static final class Marker implements AutoCloseable {
		private final ChunkLoadEvent event;
		private final boolean enabled;
		private boolean finished;

		private Marker(ChunkLoadEvent event, boolean enabled) {
			this.event = event;
			this.enabled = enabled;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public synchronized void finish(Throwable failure) {
			if (!enabled || finished) {
				return;
			}
			finished = true;
			event.success = failure == null;
			event.failureType = failure == null ? null : failure.getClass().getName();
			event.elapsedNanos = System.nanoTime() - event.startedNanos;
			event.end();
			event.commit();
		}

		@Override
		public void close() {
			finish(null);
		}
	}

	@Category("MTLRender")
	@Label("Chunk Load")
	@Description("A batch boundary for chunk loading and publication")
	private static final class ChunkLoadEvent extends Event {
		@Label("Phase")
		String phase;

		@Label("Phase Code")
		int phaseCode;

		@Label("Item Count")
		int itemCount;

		@Label("Elapsed")
		@Timespan(Timespan.NANOSECONDS)
		long elapsedNanos;

		@Label("Caller Thread")
		String callerThread;

		@Label("Success")
		boolean success;

		@Label("Failure Type")
		String failureType;

		private final long startedNanos = System.nanoTime();
	}
}
