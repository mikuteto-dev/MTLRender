package dev.mikuteto.mtlrender.pack.runtime;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.mikuteto.mtlrender.pack.PackManager;

public final class PackProgramCache<P> implements AutoCloseable {
	public interface StateSource {
		State state();
	}

	@FunctionalInterface
	public interface Loader<P> {
		ProgramResult<P> load() throws Exception;
	}

	@FunctionalInterface
	public interface PipelineFactory<P> {
		P create(String dimension, String program) throws Exception;
	}

	@FunctionalInterface
	public interface PipelineCloser<P> {
		void close(P pipeline);
	}

	public record State(long version, boolean active) {
	}

	public record Identity(long version, long generation) {
	}

	public record ProgramIdentity(GeometryPathSelector.ProgramKind kind, String name) {
		public ProgramIdentity {
			kind = Objects.requireNonNull(kind, "program kind");
			if (name == null || name.isBlank()) throw new IllegalArgumentException("program name must not be blank");
		}

		public GeometryPathSelector.ProgramIdentity selectorIdentity() {
			return new GeometryPathSelector.ProgramIdentity(kind, name);
		}
	}

	public static final class ProgramResult<P> {
		private final P pipeline;
		private final Exception failure;
		private final GenerationOwner owner;

		private interface GenerationOwner {
			void verify();
		}

		private ProgramResult(P pipeline, Exception failure, GenerationOwner owner) {
			this.pipeline = pipeline;
			this.failure = failure;
			this.owner = owner;
		}

		public static <P> ProgramResult<P> success(P pipeline) {
			return new ProgramResult<>(Objects.requireNonNull(pipeline), null, null);
		}

		public static <P> ProgramResult<P> failure(Exception failure) {
			return new ProgramResult<>(null, Objects.requireNonNull(failure), null);
		}

		private ProgramResult<P> ownedBy(GenerationOwner nextOwner) {
			return new ProgramResult<>(pipeline, failure, nextOwner);
		}

		public boolean successful() {
			return failure == null;
		}

		public Optional<P> pipeline() {
			if (owner != null) owner.verify();
			return Optional.ofNullable(pipeline);
		}

		public Optional<Exception> failure() {
			if (owner != null) owner.verify();
			return Optional.ofNullable(failure);
		}

		private Optional<P> rawPipeline() {
			return Optional.ofNullable(pipeline);
		}
	}

	public final class Generation {
		private final String dimension;
		private final Identity identity;
		private final Map<ProgramIdentity, ProgramResult<P>> results = new LinkedHashMap<>();
		private boolean retired;

		private Generation(String dimension, Identity identity) {
			this.dimension = dimension;
			this.identity = identity;
		}

		public String dimension() {
			return dimension;
		}

		public Identity identity() {
			return identity;
		}

		public boolean current() {
			synchronized (PackProgramCache.this) {
				return !retired && generations.get(dimension) == this && currentState != null
					&& currentState.version() == identity.version();
			}
		}

		public synchronized ProgramResult<P> result(String program, Loader<P> loader) {
			return result(renderIdentity(program), loader);
		}

		public synchronized ProgramResult<P> result(GeometryPathSelector.ProgramIdentity program, Loader<P> loader) {
			Objects.requireNonNull(program);
			Objects.requireNonNull(loader);
			verify();
			ProgramIdentity key = new ProgramIdentity(program.kind(), program.name());
			ProgramResult<P> result = results.get(key);
			if (result != null) return result;
			try {
				result = Objects.requireNonNull(loader.load());
			} catch (Exception exception) {
				result = ProgramResult.failure(exception);
			}
			result = result.ownedBy(this::verify);
			results.put(key, result);
			return result;
		}

		public synchronized ProgramResult<P> getOrCompile(GeometryPathSelector.ProgramIdentity program,
			Loader<P> compiler) {
			return result(program, compiler);
		}

		public synchronized Map<ProgramIdentity, ProgramResult<P>> results() {
			verify();
			return Map.copyOf(results);
		}

		private synchronized void verify() {
			if (!current()) throw new IllegalStateException("pack program generation is retired");
		}

		private synchronized void retire(Map<P, Boolean> closed) {
			if (retired) return;
			retired = true;
			for (ProgramResult<P> result : results.values()) {
				result.rawPipeline().ifPresent(pipeline -> {
					if (closed.put(pipeline, Boolean.TRUE) == null) closePipeline(pipeline);
				});
			}
			results.clear();
		}
	}

	private final StateSource source;
	private final PipelineCloser<P> closer;
	private final Map<String, Generation> generations = new LinkedHashMap<>();
	private State currentState;
	private long nextGeneration;

	public PackProgramCache(StateSource source, PipelineCloser<P> closer) {
		this.source = Objects.requireNonNull(source);
		this.closer = Objects.requireNonNull(closer);
	}

	public PackProgramCache(PackManager manager, PipelineCloser<P> closer) {
		this(() -> state(manager), closer);
	}

	private static State state(PackManager manager) {
		Objects.requireNonNull(manager, "manager");
		synchronized (manager) {
			return new State(manager.version(), manager.active().isPresent());
		}
	}

	public synchronized Optional<Generation> generation(String dimension) {
		Objects.requireNonNull(dimension);
		State state = Objects.requireNonNull(source.state());
		if (!state.active()) {
			retireCurrent();
			currentState = state;
			return Optional.empty();
		}
		if (currentState == null || !state.equals(currentState)) {
			retireCurrent();
			currentState = state;
		}
		Generation generation = generations.get(dimension);
		if (generation == null) {
			generation = new Generation(dimension, new Identity(state.version(), ++nextGeneration));
			generations.put(dimension, generation);
		}
		return Optional.of(generation);
	}

	public synchronized ProgramResult<P> result(String dimension, String program, Loader<P> loader) {
		return generation(dimension).orElseThrow(() -> new IllegalStateException("shader pack is inactive"))
			.result(program, loader);
	}

	public synchronized ProgramResult<P> result(String dimension, GeometryPathSelector.ProgramIdentity program,
		Loader<P> loader) {
		return generation(dimension).orElseThrow(() -> new IllegalStateException("shader pack is inactive"))
			.result(program, loader);
	}

	public synchronized ProgramResult<P> getOrCompile(String dimension, GeometryPathSelector.ProgramIdentity program,
		Loader<P> compiler) {
		return result(dimension, program, compiler);
	}

	public synchronized ProgramResult<P> pipeline(String dimension, String program, PipelineFactory<P> factory) {
		return result(dimension, program, () -> ProgramResult.success(factory.create(dimension, program)));
	}

	@Override
	public synchronized void close() {
		retireCurrent();
		currentState = null;
	}

	private void retireCurrent() {
		Map<P, Boolean> closed = new IdentityHashMap<>();
		for (Generation generation : generations.values()) generation.retire(closed);
		generations.clear();
	}

	private void closePipeline(P pipeline) {
		closer.close(pipeline);
	}

	private static GeometryPathSelector.ProgramIdentity renderIdentity(String program) {
		return new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, program);
	}
}
