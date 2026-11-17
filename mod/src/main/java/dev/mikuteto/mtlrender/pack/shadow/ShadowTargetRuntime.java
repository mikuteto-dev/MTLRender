package dev.mikuteto.mtlrender.pack.shadow;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class ShadowTargetRuntime implements AutoCloseable {
	public interface TargetSet extends AutoCloseable {
		long generation();

		@Override
		void close();
	}

	@FunctionalInterface
	public interface Factory {
		TargetSet create(ShadowTargetPlan plan);
	}

	private record Retired(long frame, TargetSet targets) {
	}

	private final Factory factory;
	private final LongSupplier submittedFrames;
	private final LongSupplier completedFrames;
	private final ArrayDeque<Retired> retired = new ArrayDeque<>();
	private TargetSet current;
	private boolean closed;

	public ShadowTargetRuntime(Factory factory, LongSupplier submittedFrames, LongSupplier completedFrames) {
		this.factory = Objects.requireNonNull(factory, "factory");
		this.submittedFrames = Objects.requireNonNull(submittedFrames, "submitted frames");
		this.completedFrames = Objects.requireNonNull(completedFrames, "completed frames");
	}

	public synchronized Optional<TargetSet> current() {
		collect(completedFrames.getAsLong());
		return Optional.ofNullable(current);
	}

	public synchronized TargetSet publish(ShadowTargetPlan plan) {
		Objects.requireNonNull(plan, "plan");
		if (closed) throw new IllegalStateException("shadow target runtime is closed");
		if (current != null && current.generation() == plan.generation()) return current;
		TargetSet next = Objects.requireNonNull(factory.create(plan), "target set");
		if (next.generation() != plan.generation()) {
			next.close();
			throw new IllegalArgumentException("target generation does not match plan");
		}
		if (current != null) retired.addLast(new Retired(submittedFrames.getAsLong(), current));
		current = next;
		collect(completedFrames.getAsLong());
		return next;
	}

	public synchronized void collect(long completedFrame) {
		while (!retired.isEmpty() && retired.peekFirst().frame() < completedFrame) {
			retired.removeFirst().targets().close();
		}
	}

	public synchronized int retiredCount() {
		return retired.size();
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		if (current != null) {
			retired.addLast(new Retired(submittedFrames.getAsLong(), current));
			current = null;
		}
		collect(completedFrames.getAsLong());
	}
}
