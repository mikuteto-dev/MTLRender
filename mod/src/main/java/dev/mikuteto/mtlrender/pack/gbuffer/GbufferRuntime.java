package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.Objects;
import java.util.Optional;

public final class GbufferRuntime implements AutoCloseable {
	public enum Transition {
		PACK_RELOAD,
		DIMENSION_CHANGE,
		RESIZE,
		WORLD_UNLOAD,
		PACK_DISABLE,
		BACKEND_CLOSE
	}

	public interface Providers {
		void install();

		void remove();
	}

	@FunctionalInterface
	public interface RetirementOwner {
		void retire(Generation generation, Transition transition);
	}

	public record Identity(String pack, long packVersion, String dimension, long targetPlanGeneration,
		long pipelineSourceGeneration, long alphaBlendGeneration, long drawUniformGeneration) {
		public Identity {
			Objects.requireNonNull(pack, "pack");
			Objects.requireNonNull(dimension, "dimension");
			if (pack.isBlank()) throw new IllegalArgumentException("pack must not be blank");
			if (dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
		}
	}

	public record Generation(Identity identity, Providers providers) {
		public Generation {
			Objects.requireNonNull(identity, "identity");
			Objects.requireNonNull(providers, "providers");
		}
	}

	public record FrameSnapshot(long frame, Optional<Generation> generation) {
		public FrameSnapshot {
			if (frame <= 0L) throw new IllegalArgumentException("frame must be positive");
			generation = Objects.requireNonNull(generation, "generation");
		}
	}

	private final RetirementOwner retirement;
	private Generation published;
	private FrameSnapshot activeFrame;
	private long nextFrame;
	private boolean closed;

	public GbufferRuntime(RetirementOwner retirement) {
		this.retirement = Objects.requireNonNull(retirement, "retirement");
	}

	public synchronized Optional<Generation> published() {
		return Optional.ofNullable(published);
	}

	public synchronized FrameSnapshot beginFrame() {
		if (closed) throw new IllegalStateException("gbuffer runtime is closed");
		if (activeFrame != null) throw new IllegalStateException("gbuffer frame is already open");
		FrameSnapshot snapshot = new FrameSnapshot(++nextFrame, Optional.ofNullable(published));
		activeFrame = snapshot;
		return snapshot;
	}

	public synchronized void endFrame(FrameSnapshot frame) {
		Objects.requireNonNull(frame, "frame");
		if (activeFrame == null || activeFrame.frame() != frame.frame()) {
			throw new IllegalStateException("gbuffer frame is not active");
		}
		activeFrame = null;
	}

	public synchronized void publish(Optional<Generation> next) {
		publish(Transition.PACK_RELOAD, next);
	}

	public synchronized void publish(Transition transition, Optional<Generation> next) {
		if (closed) throw new IllegalStateException("gbuffer runtime is closed");
		Objects.requireNonNull(transition, "transition");
		Objects.requireNonNull(next, "next");
		Generation candidate = next.orElse(null);
		if (candidate != null) {
			Objects.requireNonNull(candidate.identity(), "generation identity");
			Objects.requireNonNull(candidate.providers(), "generation providers");
		}
		if (published != null) {
			published.providers().remove();
			retirement.retire(published, transition);
			published = null;
		}
		if (candidate != null) {
			try {
				candidate.providers().install();
			} catch (RuntimeException exception) {
				candidate.providers().remove();
				throw exception;
			}
		}
		published = candidate;
	}

	public synchronized void dimensionChange(Optional<Generation> next) {
		publish(Transition.DIMENSION_CHANGE, next);
	}

	public synchronized void resize(Optional<Generation> next) {
		publish(Transition.RESIZE, next);
	}

	public synchronized void worldUnload() {
		publish(Transition.WORLD_UNLOAD, Optional.empty());
	}

	public synchronized void disablePack() {
		publish(Transition.PACK_DISABLE, Optional.empty());
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		if (activeFrame != null) throw new IllegalStateException("cannot close during a gbuffer frame");
		if (published != null) {
			published.providers().remove();
			retirement.retire(published, Transition.BACKEND_CLOSE);
			published = null;
		}
		closed = true;
	}
}
