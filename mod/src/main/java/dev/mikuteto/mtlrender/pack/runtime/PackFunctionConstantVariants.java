package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.uniform.UniformValues;
import dev.mikuteto.mtlrender.pack.uniform.Value;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class PackFunctionConstantVariants implements AutoCloseable {
	@FunctionalInterface
	public interface Compiler {
		PackProgramArtifact compile(PackFunctionConstants constants) throws Exception;
	}

	public record Selection(PackProgramArtifact artifact, PackFunctionConstants constants,
		boolean specialised, boolean compiling) {
		public Selection {
			Objects.requireNonNull(artifact, "artifact");
			Objects.requireNonNull(constants, "constants");
		}
	}

	private final PackVariantPolicy policy;
	private final PackProgramArtifact generic;
	private final Compiler compiler;
	private final Executor executor;
	private final Map<PackFunctionConstants, PackProgramArtifact> published = new ConcurrentHashMap<>();
	private final Set<PackFunctionConstants> compiling = ConcurrentHashMap.newKeySet();
	private final Set<PackFunctionConstants> failed = ConcurrentHashMap.newKeySet();
	private int reserved;
	private boolean closed;

	public PackFunctionConstantVariants(PackVariantPolicy policy, PackProgramArtifact generic,
		Compiler compiler, Executor executor) {
		this.policy = Objects.requireNonNull(policy, "policy");
		this.generic = Objects.requireNonNull(generic, "generic artifact");
		this.compiler = Objects.requireNonNull(compiler, "compiler");
		this.executor = Objects.requireNonNull(executor, "executor");
	}

	public PackFunctionConstantVariants(PackVariantPolicy policy, PackProgramArtifact generic,
		Compiler compiler) {
		this(policy, generic, compiler, Executors.newVirtualThreadPerTaskExecutor());
	}

	public PackFunctionConstantVariants(PackVariantPolicy policy, PackProgramArtifact generic,
		PackPipelineCache cache, PackPipelineCache.Request template, Executor executor) {
		this(policy, generic, constants -> cache.resolve(new PackPipelineCache.Request(template.program(),
			template.translationKey(), template.pass(), constants)).artifact().orElseThrow(() ->
			new IllegalStateException("specialised pack pipeline compilation failed")), executor);
	}

	public Selection select(PackFunctionConstants requested) {
		Objects.requireNonNull(requested, "requested constants");
		if (requested.hardVariantCap() != policy.hardVariantCap()) {
			throw new IllegalArgumentException("variant policy cap mismatch");
		}
		if (!requested.isSpecialised()) return new Selection(generic, policy.genericFallback(), false, false);
		PackProgramArtifact ready = published.get(requested);
		if (ready != null) return new Selection(ready, requested, true, false);
		boolean queued = schedule(requested);
		return new Selection(generic, policy.genericFallback(), false, queued);
	}

	public Selection request(PackFunctionConstants requested) {
		return select(requested);
	}

	public int publishedCount() {
		return published.size();
	}

	public int compilingCount() {
		return compiling.size();
	}

	public static PackFunctionConstants constants(PackVariantPolicy policy, ShaderPack pack,
		UniformValues uniforms) {
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(uniforms, "uniforms");
		Map<String, String> options = pack.optionValues().entrySet().stream()
			.filter(entry -> policy.optionNames().contains(entry.getKey()))
			.collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
		Value eye = uniforms.value(PackFunctionConstants.IS_EYE_IN_WATER);
		Value rain = uniforms.value("rainStrength");
		boolean eyeInWater = eye != null && eye.bool(0);
		boolean rainZero = rain == null || rain.number(0) == 0.0;
		return policy.requested(options, eyeInWater, rainZero);
	}

	@Override
	public synchronized void close() {
		closed = true;
		compiling.clear();
	}

	private boolean schedule(PackFunctionConstants constants) {
		synchronized (this) {
			if (closed || published.containsKey(constants) || failed.contains(constants) || compiling.contains(constants)) return false;
			if (reserved >= policy.hardVariantCap()) {
				failed.add(constants);
				return false;
			}
			reserved++;
			compiling.add(constants);
		}
		executor.execute(() -> compile(constants));
		return true;
	}

	private void compile(PackFunctionConstants constants) {
		try {
			PackProgramArtifact artifact = Objects.requireNonNull(compiler.compile(constants), "variant artifact");
			published.putIfAbsent(constants, artifact);
		} catch (Exception exception) {
			failed.add(constants);
		} finally {
			synchronized (this) {
				compiling.remove(constants);
			}
		}
	}
}
