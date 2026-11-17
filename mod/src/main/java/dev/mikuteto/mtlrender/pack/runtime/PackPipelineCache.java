package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.Program;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PackPipelineCache implements AutoCloseable {
	public interface StateSource {
		State state();
	}

	@FunctionalInterface
	public interface Translator {
		PackTranslationArtifact translate(PackProgramRequest request, PackCacheKey key) throws Exception;
	}

	@FunctionalInterface
	public interface PipelineFactory {
		long create(PackPipelineDesc descriptor) throws Exception;
	}

	@FunctionalInterface
	public interface PipelineCloser {
		void close(long handle);
	}

	@FunctionalInterface
	public interface DescriptorFactory {
		PackPipelineDesc describe(PackProgramRequest request, PackTranslationArtifact translation,
			PassKind pass, PackFunctionConstants constants) throws Exception;
	}

	public record State(long version, boolean active) {
	}

	public static PipelineFactory metalPipelineFactory() {
		return MetalAccess::createPackPipeline;
	}

	public static PipelineCloser metalPipelineCloser() {
		return MetalAccess::destroyPackPipeline;
	}

	public record Request(PackProgramRequest program, PackCacheKey translationKey, PassKind pass,
		PackFunctionConstants constants) {
		public Request {
			Objects.requireNonNull(program, "program");
			Objects.requireNonNull(translationKey, "translation key");
			Objects.requireNonNull(pass, "pass");
			Objects.requireNonNull(constants, "function constants");
		}
	}

	public static final class Result {
		private final PackProgramArtifact artifact;
		private final Exception failure;

		private Result(PackProgramArtifact artifact, Exception failure) {
			this.artifact = artifact;
			this.failure = failure;
		}

		public static Result success(PackProgramArtifact artifact) {
			return new Result(Objects.requireNonNull(artifact), null);
		}

		public static Result failure(Exception failure) {
			return new Result(null, Objects.requireNonNull(failure));
		}

		public boolean successful() { return artifact != null; }
		public Optional<PackProgramArtifact> artifact() { return Optional.ofNullable(artifact); }
		public Optional<Exception> failure() { return Optional.ofNullable(failure); }
	}

	public final class Generation {
		private final long version;
		private final Map<PipelineIdentity, Result> results = new LinkedHashMap<>();
		private boolean retired;

		private Generation(long version) { this.version = version; }
		public long version() { return version; }
		public boolean current() {
			synchronized (PackPipelineCache.this) { return !retired && generation == this; }
		}
		private synchronized Result resolve(Request request) {
			if (retired) throw new IllegalStateException("pack pipeline generation is retired");
			PackTranslationArtifact translation;
			try {
				translation = translations.computeIfAbsent(request.translationKey(), key -> translate(request.program(), key));
				if (translation == null) return Result.failure(new IllegalStateException("translation returned null"));
			} catch (RuntimeException exception) {
				Result result = Result.failure(exception);
				results.putIfAbsent(identity(request, null), result);
				return result;
			}
			PackPipelineDesc descriptor;
			try {
				descriptor = descriptorFactory.describe(request.program(), translation, request.pass(), request.constants());
				if (descriptor == null) throw new IllegalStateException("descriptor factory returned null");
			} catch (Exception exception) {
				Result result = Result.failure(exception);
				results.putIfAbsent(identity(request, null), result);
				return result;
			}
			PipelineIdentity identity = identity(request, descriptor);
			Result cached = results.get(identity);
			if (cached != null) return cached;
			Result result;
			try {
				long handle = pipelineFactory.create(descriptor);
				if (handle <= 0) throw new IllegalStateException("pack pipeline creation returned an invalid handle");
				RuntimeProgram runtime = new RuntimeProgram(request.program().dimension(), request.program().program(),
					request.program().program(), handle);
				Map<Program.Stage, String> sources = Map.of(Program.Stage.VERTEX, translation.vertexMsl(),
					Program.Stage.FRAGMENT, translation.fragmentMsl());
				result = Result.success(new PackProgramArtifact(request.program(), runtime, sources));
			} catch (Exception exception) {
				result = Result.failure(exception);
			}
			results.put(identity, result);
			return result;
		}

		private synchronized void retire(Map<Long, Boolean> closed) {
			if (retired) return;
			retired = true;
			for (Result result : results.values()) {
				result.artifact().ifPresent(artifact -> {
					long handle = artifact.runtime().renderPipeline();
					if (handle > 0 && closed.put(handle, Boolean.TRUE) == null) pipelineCloser.close(handle);
				});
			}
			results.clear();
		}
	}

	private record PipelineIdentity(String dimension, String program, PassKind pass,
		PackCacheKey translationKey, PackFunctionConstants constants, PackPipelineDesc descriptor) {
	}

	private final StateSource source;
	private final PackTranslationDiskCache diskCache;
	private final Translator translator;
	private final DescriptorFactory descriptorFactory;
	private final PipelineFactory pipelineFactory;
	private final PipelineCloser pipelineCloser;
	private final Map<PackCacheKey, PackTranslationArtifact> translations = new LinkedHashMap<>();
	private final Map<PackCacheKey, Exception> translationFailures = new LinkedHashMap<>();
	private Generation generation;
	private State state;

	public PackPipelineCache(StateSource source, PackTranslationDiskCache diskCache, Translator translator,
		DescriptorFactory descriptorFactory, PipelineFactory pipelineFactory, PipelineCloser pipelineCloser) {
		this.source = Objects.requireNonNull(source);
		this.diskCache = Objects.requireNonNull(diskCache);
		this.translator = Objects.requireNonNull(translator);
		this.descriptorFactory = Objects.requireNonNull(descriptorFactory);
		this.pipelineFactory = Objects.requireNonNull(pipelineFactory);
		this.pipelineCloser = Objects.requireNonNull(pipelineCloser);
	}

	public PackPipelineCache(PackManager manager, PackTranslationDiskCache diskCache, Translator translator,
		DescriptorFactory descriptorFactory, PipelineFactory pipelineFactory, PipelineCloser pipelineCloser) {
		this(() -> new State(manager.version(), manager.active().isPresent()), diskCache, translator,
			descriptorFactory, pipelineFactory, pipelineCloser);
	}

	public synchronized Optional<Generation> generation() {
		State next = Objects.requireNonNull(source.state(), "pack state");
		if (!next.active()) {
			retire();
			return Optional.empty();
		}
		if (generation == null || !next.equals(state)) {
			retire();
			state = next;
			generation = new Generation(next.version());
		}
		return Optional.of(generation);
	}

	public synchronized Result resolve(Request request) {
		Objects.requireNonNull(request, "request");
		return generation().map(value -> value.resolve(request)).orElseGet(() ->
			Result.failure(new IllegalStateException("shader pack is inactive")));
	}

	public synchronized Result resolve(Request request, PackVariantPolicy policy) {
		Objects.requireNonNull(policy, "variant policy");
		Objects.requireNonNull(request, "request");
		if (request.constants().hardVariantCap() != policy.hardVariantCap()) {
			throw new IllegalArgumentException("variant policy cap mismatch");
		}
		return resolve(request);
	}

	@Override
	public synchronized void close() {
		retire();
		state = null;
	}

	private PackTranslationArtifact translate(PackProgramRequest request, PackCacheKey key) {
		Optional<PackTranslationArtifact> restored = diskCache.read(key);
		if (restored.isPresent()) return restored.orElseThrow();
		Exception previous = translationFailures.get(key);
		if (previous != null) throw new TranslationFailure(previous);
		try {
			PackTranslationArtifact artifact = Objects.requireNonNull(translator.translate(request, key), "translation");
			try { diskCache.write(key, artifact); } catch (Exception ignored) { }
			return artifact;
		} catch (Exception exception) {
			translationFailures.put(key, exception);
			throw new TranslationFailure(exception);
		}
	}

	private PipelineIdentity identity(Request request, PackPipelineDesc descriptor) {
		return new PipelineIdentity(request.program().dimension(), request.program().program(), request.pass(),
			request.translationKey(), request.constants(), descriptor);
	}

	private void retire() {
		if (generation == null) return;
		Map<Long, Boolean> closed = new java.util.HashMap<>();
		generation.retire(closed);
		generation = null;
		translations.clear();
		translationFailures.clear();
	}

	private static final class TranslationFailure extends RuntimeException {
		private TranslationFailure(Exception cause) { super(cause); }
	}
}
