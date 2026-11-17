package dev.mikuteto.mtlrender.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

public final class SectionBatch {
	public static final int MAX_ITEMS = 4;
	private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
	public static final long DEADLINE_NANOS = 2_000_000L;

	private SectionBatch() {
	}

	public enum FlushReason {
		INCOMPATIBLE_INPUTS,
		INELIGIBLE_DYNAMIC_CONTENT,
		CANCELLATION,
		QUEUE_EXHAUSTION,
		TIMEOUT,
		NATIVE_FAILURE,
		ONE_SECTION_FALLBACK,
		LIMIT_REACHED
	}

	public enum Status {
		NATIVE,
		INCOMPATIBLE_INPUTS,
		INELIGIBLE_DYNAMIC_CONTENT,
		CANCELLED,
		QUEUE_EXHAUSTION,
		TIMEOUT,
		NATIVE_FAILURE,
		ONE_SECTION_FALLBACK
	}

	public static final class Compatibility {
		private final long tableGeneration;
		private final boolean ambientOcclusion;
		private final boolean cutoutLeaves;
		private final Object blockColorIdentity;
		private final Object modelTableIdentity;

		public Compatibility(
			long tableGeneration,
			boolean ambientOcclusion,
			boolean cutoutLeaves,
			Object blockColorIdentity,
			Object modelTableIdentity
		) {
			this.tableGeneration = tableGeneration;
			this.ambientOcclusion = ambientOcclusion;
			this.cutoutLeaves = cutoutLeaves;
			this.blockColorIdentity = blockColorIdentity;
			this.modelTableIdentity = modelTableIdentity;
		}

		public long tableGeneration() {
			return tableGeneration;
		}

		public boolean ambientOcclusion() {
			return ambientOcclusion;
		}

		public boolean cutoutLeaves() {
			return cutoutLeaves;
		}

		public Object blockColorIdentity() {
			return blockColorIdentity;
		}

		public Object modelTableIdentity() {
			return modelTableIdentity;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof Compatibility that)) {
				return false;
			}
			return tableGeneration == that.tableGeneration
				&& ambientOcclusion == that.ambientOcclusion
				&& cutoutLeaves == that.cutoutLeaves
				&& blockColorIdentity == that.blockColorIdentity
				&& modelTableIdentity == that.modelTableIdentity;
		}

		@Override
		public int hashCode() {
			return Objects.hash(
				tableGeneration,
				ambientOcclusion,
				cutoutLeaves,
				System.identityHashCode(blockColorIdentity),
				System.identityHashCode(modelTableIdentity)
			);
		}
	}

	public record Request(
		SectionPos sectionPos,
		RenderSectionRegion region,
		VertexSorting vertexSorting,
		SectionBufferBuilderPack buffers,
		Compatibility compatibility,
		boolean eligible
	) {
		public long key() {
			return sectionPos.asLong();
		}
	}

	public static final class Result {
		private final SectionCompiler.Results value;
		private final Status status;
		private final Runnable releaser;
		private boolean released;

		private Result(SectionCompiler.Results value, Status status, Runnable releaser) {
			this.value = value;
			this.status = status;
			this.releaser = releaser;
		}

		public static Result nativeResult(SectionCompiler.Results value) {
			return new Result(value, Status.NATIVE, value == null ? () -> { } : value::release);
		}

		public static Result nativeResult(SectionCompiler.Results value, Runnable releaser) {
			return new Result(value, Status.NATIVE, Objects.requireNonNull(releaser));
		}

		private static Result status(Status status) {
			return new Result(null, status, () -> { });
		}

		public SectionCompiler.Results value() {
			return value;
		}

		public Status status() {
			return status;
		}

		public void release() {
			if (!released) {
				released = true;
				releaser.run();
			}
		}
	}

	public static final class Scope implements AutoCloseable {
		private final Compatibility compatibility;
		private final long deadline;
		private final LongSupplier clock;
		private final Thread owner;
		private final Map<Long, Result> results = new HashMap<>(MAX_ITEMS);
		private int itemCount;
		private FlushReason flushReason;
		private boolean cancelled;
		private boolean closed;

		public Scope(Compatibility compatibility) {
			this(compatibility, System.nanoTime() + DEADLINE_NANOS, System::nanoTime);
		}

		public Scope(Compatibility compatibility, long deadline) {
			this(compatibility, deadline, System::nanoTime);
		}

		Scope(Compatibility compatibility, long deadline, LongSupplier clock) {
			this.compatibility = Objects.requireNonNull(compatibility);
			this.deadline = deadline;
			this.clock = Objects.requireNonNull(clock);
			this.owner = Thread.currentThread();
		}

		public boolean accept(Request request) {
			checkOwner();
			if (closed || cancelled) {
				return false;
			}
			if (clock.getAsLong() >= deadline) {
				flushReason = FlushReason.TIMEOUT;
				return false;
			}
			if (!request.eligible()) {
				flushReason = FlushReason.INELIGIBLE_DYNAMIC_CONTENT;
				return false;
			}
			if (!compatibility.equals(request.compatibility())) {
				flushReason = FlushReason.INCOMPATIBLE_INPUTS;
				return false;
			}
			if (itemCount >= MAX_ITEMS) {
				flushReason = FlushReason.LIMIT_REACHED;
				return false;
			}
			itemCount++;
			return true;
		}

		public void install(Request request, SectionCompiler.Results value) {
			install(request, Result.nativeResult(value));
		}

		public void install(Request request, Result result) {
			checkOwner();
			Objects.requireNonNull(request);
			Objects.requireNonNull(result);
			if (closed || cancelled || !compatibility.equals(request.compatibility())) {
				result.release();
				return;
			}
			Result previous = results.put(request.key(), result);
			if (previous != null) {
				previous.release();
			}
		}

		public Result take(long sectionKey) {
			checkOwner();
			Result result = results.remove(sectionKey);
			if (result != null) {
				return result;
			}
			flushReason = FlushReason.ONE_SECTION_FALLBACK;
			return Result.status(Status.ONE_SECTION_FALLBACK);
		}

		public Result take(SectionPos sectionPos) {
			return take(sectionPos.asLong());
		}

		public Result take(Request request) {
			return take(request.key());
		}

		public void flush(FlushReason reason) {
			checkOwner();
			if (!closed) {
				flushReason = Objects.requireNonNull(reason);
				if (reason == FlushReason.CANCELLATION || reason == FlushReason.NATIVE_FAILURE) {
					discard();
				}
			}
		}

		public void cancel() {
			checkOwner();
			if (!closed) {
				cancelled = true;
				flushReason = FlushReason.CANCELLATION;
				discard();
			}
		}

		public FlushReason flushReason() {
			return flushReason;
		}

		public static Scope current() {
			Scope scope = ACTIVE.get();
			return scope == null || scope.closed ? null : scope;
		}

		public Handoff bind() {
			checkOwner();
			Scope previous = ACTIVE.get();
			ACTIVE.set(this);
			return new Handoff(previous);
		}

		public int size() {
			return itemCount;
		}

		private void discard() {
			for (Result result : results.values()) {
				result.release();
			}
			results.clear();
		}

		private void checkOwner() {
			if (Thread.currentThread() != owner) {
				throw new IllegalStateException("section batch scope is thread-confined");
			}
		}

		public final class Handoff implements AutoCloseable {
			private final Scope previous;
			private boolean closed;

			private Handoff(Scope previous) {
				this.previous = previous;
			}

			@Override
			public void close() {
				if (!closed) {
					closed = true;
					if (ACTIVE.get() == Scope.this) {
						if (previous == null) {
							ACTIVE.remove();
						} else {
							ACTIVE.set(previous);
						}
					}
				}
			}
		}

		@Override
		public void close() {
			checkOwner();
			if (!closed) {
				closed = true;
				discard();
				if (ACTIVE.get() == this) {
					ACTIVE.remove();
				}
			}
		}
	}
}
