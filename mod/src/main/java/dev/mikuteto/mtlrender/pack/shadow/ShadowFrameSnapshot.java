package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.gbuffer.GbufferRuntime;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.pack.runtime.PackUniformState;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ShadowFrameSnapshot {
	public record PackIdentity(String name, long version) {
		public PackIdentity {
			Objects.requireNonNull(name, "pack name");
			if (name.isBlank()) throw new IllegalArgumentException("pack name must not be blank");
			if (version < 0L) throw new IllegalArgumentException("pack version must not be negative");
		}
	}

	public record Generations(long reload, long target, long programSource, long uniform) {
		public Generations {
			if (reload < 0L || target < 0L || programSource < 0L || uniform < 0L) {
				throw new IllegalArgumentException("shadow frame generations must not be negative");
			}
		}

		public boolean same(Generations other) {
			return equals(Objects.requireNonNull(other, "other generations"));
		}
	}

	private final Optional<PackIdentity> pack;
	private final Optional<String> dimension;
	private final ShadowPassPlan plan;
	private final Generations generations;
	private final List<ShadowProgramSelection> programs;
	private final boolean prepareBeforeShadow;

	private ShadowFrameSnapshot(Optional<PackIdentity> pack, Optional<String> dimension, ShadowPassPlan plan,
		Generations generations, List<ShadowProgramSelection> programs, boolean prepareBeforeShadow) {
		this.pack = Objects.requireNonNull(pack, "pack");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		this.plan = Objects.requireNonNull(plan, "plan");
		this.generations = Objects.requireNonNull(generations, "generations");
		this.programs = List.copyOf(programs);
		this.prepareBeforeShadow = prepareBeforeShadow;
		if (pack.isEmpty() != dimension.isEmpty()) {
			throw new IllegalArgumentException("pack and dimension presence must match");
		}
		if (pack.isEmpty() && !this.programs.isEmpty()) {
			throw new IllegalArgumentException("no-pack snapshot must not contain programs");
		}
	}

	public static ShadowFrameSnapshot noPack() {
		return new ShadowFrameSnapshot(Optional.empty(), Optional.empty(), ShadowPassPlan.disabled(),
			new Generations(0L, 0L, 0L, 0L), List.of(), false);
	}

	public static ShadowFrameSnapshot enabled(PackIdentity pack, String dimension, long reloadGeneration,
		ShadowPassPlan plan, long targetGeneration, long programSourceGeneration, long uniformGeneration,
		List<ShadowProgramSelection> programs, boolean prepareBeforeShadow) {
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.isBlank()) throw new IllegalArgumentException("dimension must not be blank");
		return new ShadowFrameSnapshot(Optional.of(pack), Optional.of(dimension), plan,
			new Generations(reloadGeneration, targetGeneration, programSourceGeneration, uniformGeneration), programs,
			prepareBeforeShadow);
	}

	public static ShadowFrameSnapshot enabled(PackRuntimeService.PackSnapshot pack,
		GbufferRuntime.Identity generation, ShadowPassPlan plan, ShadowTargetRuntime.TargetSet targets,
		PackUniformState uniforms, List<ShadowProgramSelection> programs, boolean prepareBeforeShadow) {
		Objects.requireNonNull(pack, "pack snapshot");
		Objects.requireNonNull(generation, "gbuffer generation");
		if (pack.version() != generation.packVersion()) {
			throw new IllegalArgumentException("pack and gbuffer generations do not match");
		}
		if (!pack.pack().baseFolder().equals(generation.pack())) {
			throw new IllegalArgumentException("pack identities do not match");
		}
		return enabled(new PackIdentity(pack.pack().baseFolder(), pack.version()), generation.dimension(), pack.version(),
			plan, targets, generation.pipelineSourceGeneration(), uniforms, programs, prepareBeforeShadow,
			generation.targetPlanGeneration(), generation.drawUniformGeneration());
	}

	private static ShadowFrameSnapshot enabled(PackIdentity pack, String dimension, long reloadGeneration,
		ShadowPassPlan plan, ShadowTargetRuntime.TargetSet targets, long programSourceGeneration,
		PackUniformState uniforms, List<ShadowProgramSelection> programs, boolean prepareBeforeShadow,
		long expectedTargetGeneration, long expectedUniformGeneration) {
		Objects.requireNonNull(targets, "targets");
		Objects.requireNonNull(uniforms, "uniforms");
		if (targets.generation() != expectedTargetGeneration || uniforms.frame() != expectedUniformGeneration) {
			throw new IllegalArgumentException("gbuffer frame generations do not match");
		}
		return enabled(pack, dimension, reloadGeneration, plan, targets.generation(), programSourceGeneration,
			uniforms.frame(), programs, prepareBeforeShadow);
	}

	public static ShadowFrameSnapshot enabled(PackIdentity pack, String dimension, long reloadGeneration,
		ShadowPassPlan plan, ShadowTargetRuntime.TargetSet targets, long programSourceGeneration,
		PackUniformState uniforms, List<ShadowProgramSelection> programs, boolean prepareBeforeShadow) {
		Objects.requireNonNull(targets, "targets");
		Objects.requireNonNull(uniforms, "uniforms");
		if (!uniforms.collected()) throw new IllegalArgumentException("uniforms must be collected");
		return enabled(pack, dimension, reloadGeneration, plan, targets.generation(), programSourceGeneration,
			uniforms.frame(), programs, prepareBeforeShadow);
	}

	public boolean enabled() { return pack.isPresent(); }
	public Optional<PackIdentity> pack() { return pack; }
	public Optional<String> dimension() { return dimension; }
	public ShadowPassPlan plan() { return plan; }
	public ShadowPassPlan shadowPassPlan() { return plan; }
	public Generations generations() { return generations; }
	public long reloadGeneration() { return generations.reload(); }
	public long targetGeneration() { return generations.target(); }
	public long programSourceGeneration() { return generations.programSource(); }
	public long uniformGeneration() { return generations.uniform(); }
	public List<ShadowProgramSelection> programs() { return programs; }
	public boolean prepareBeforeShadow() { return prepareBeforeShadow; }

	public void validate(Generations expected) {
		if (!generations.same(expected)) throw new IllegalArgumentException("shadow frame generations do not match");
	}

	public void validate(long reloadGeneration, long targetGeneration, long programSourceGeneration,
		long uniformGeneration) {
		validate(new Generations(reloadGeneration, targetGeneration, programSourceGeneration, uniformGeneration));
	}
}
