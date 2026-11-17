package dev.mikuteto.mtlrender.pack.shadow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ShadowPassExecutor {
	@FunctionalInterface
	public interface MatrixCalculator {
		ShadowMatrices calculate(ShadowFrameSnapshot frame);
	}

	@FunctionalInterface
	public interface UniformPublisher {
		void publish(ShadowFrameSnapshot frame, ShadowMatrices matrices);
	}

	@FunctionalInterface
	public interface ProgramResolver {
		List<ShadowProgramSelection> resolve(ShadowFrameSnapshot frame);
	}

	@FunctionalInterface
	public interface PipelineResolver {
		void apply(ShadowFrameSnapshot frame, ShadowProgramSelection program, ShadowPipelinePolicy policy);
	}

	@FunctionalInterface
	public interface TerrainRenderer {
		void render(ShadowPassPlan plan, ShadowPassPlan.CullingMode cullingMode, boolean translucent);
	}

	@FunctionalInterface
	public interface FeatureRenderer {
		void render(ShadowPassPlan plan, boolean entities, boolean blockEntities);
	}

	public interface FeatureBridge {
		Scope enter(ShadowPassPlan plan, List<ShadowProgramSelection> programs);
		void clear();

		interface Scope extends AutoCloseable {
			@Override
			void close();
		}
	}

	public interface TargetRuntime {
		Target acquire(ShadowFrameSnapshot frame);
		void clear(Target target);
		void finalizeTarget(Target target);
		void copyDepth(Target target);

		interface Target {
			long generation();
		}
	}

	@FunctionalInterface
	public interface Prepare {
		void execute(ShadowFrameSnapshot frame);
	}

	@FunctionalInterface
	public interface Shadowcomp {
		void execute(List<ShadowProgramSelection> programs);
	}

	public record Result(boolean rendered, boolean finalized, boolean shadowcomp, List<String> stages) {
		public Result {
			stages = List.copyOf(stages);
		}
	}

	private final MatrixCalculator matrices;
	private final UniformPublisher uniforms;
	private final ProgramResolver programs;
	private final PipelineResolver pipelines;
	private final TerrainRenderer terrain;
	private final FeatureRenderer features;
	private final FeatureBridge bridge;
	private final TargetRuntime targets;
	private final Prepare prepare;
	private final Shadowcomp shadowcomp;

	public ShadowPassExecutor(MatrixCalculator matrices, UniformPublisher uniforms, ProgramResolver programs,
		PipelineResolver pipelines, TerrainRenderer terrain, FeatureRenderer features, FeatureBridge bridge,
		TargetRuntime targets, Shadowcomp shadowcomp) {
		this(matrices, uniforms, programs, pipelines, terrain, features, bridge, targets, frame -> { }, shadowcomp);
	}

	public ShadowPassExecutor(MatrixCalculator matrices, UniformPublisher uniforms, ProgramResolver programs,
		PipelineResolver pipelines, TerrainRenderer terrain, FeatureRenderer features, FeatureBridge bridge,
		TargetRuntime targets, Prepare prepare, Shadowcomp shadowcomp) {
		this.matrices = Objects.requireNonNull(matrices, "matrix calculator");
		this.uniforms = Objects.requireNonNull(uniforms, "uniform publisher");
		this.programs = Objects.requireNonNull(programs, "program resolver");
		this.pipelines = Objects.requireNonNull(pipelines, "pipeline resolver");
		this.terrain = Objects.requireNonNull(terrain, "terrain renderer");
		this.features = Objects.requireNonNull(features, "feature renderer");
		this.bridge = Objects.requireNonNull(bridge, "feature bridge");
		this.targets = Objects.requireNonNull(targets, "target runtime");
		this.prepare = Objects.requireNonNull(prepare, "prepare");
		this.shadowcomp = Objects.requireNonNull(shadowcomp, "shadowcomp");
	}

	public Result execute(ShadowFrameSnapshot frame, ShadowFrameSnapshot.Generations expected) {
		Objects.requireNonNull(frame, "shadow frame");
		Objects.requireNonNull(expected, "expected generations");
		if (!frame.enabled()) {
			return new Result(false, false, false, List.of());
		}
		try {
			frame.validate(expected);
		} catch (RuntimeException exception) {
			bridge.clear();
			throw exception;
		}
		ShadowMatrices calculated;
		try {
			calculated = Objects.requireNonNull(matrices.calculate(frame), "shadow matrices");
			uniforms.publish(frame, calculated);
		} catch (RuntimeException exception) {
			bridge.clear();
			return new Result(false, false, false, List.of("matrices"));
		}
		TargetRuntime.Target target;
		try {
			target = Objects.requireNonNull(targets.acquire(frame), "shadow target");
			if (target.generation() != frame.targetGeneration()) throw new IllegalArgumentException("shadow target generation mismatch");
		} catch (RuntimeException exception) {
			bridge.clear();
			return new Result(false, false, false, List.of("matrices", "uniforms"));
		}
		try {
			targets.clear(target);
		} catch (RuntimeException ignored) {
		}
		if (frame.prepareBeforeShadow()) {
			try {
				prepare.execute(frame);
			} catch (RuntimeException ignored) {
			}
		}
		List<ShadowProgramSelection> resolved;
		try {
			resolved = List.copyOf(programs.resolve(frame));
		} catch (RuntimeException exception) {
			resolved = List.of();
		}
		java.util.ArrayList<ShadowProgramSelection> usable = new java.util.ArrayList<>();
		for (ShadowProgramSelection program : resolved) {
			try {
				pipelines.apply(frame, program, new ShadowPipelinePolicy());
				usable.add(program);
			} catch (RuntimeException exception) {
			}
		}
		resolved = List.copyOf(usable);
		boolean rendered = false;
		FeatureBridge.Scope scope = null;
		try {
			scope = bridge.enter(frame.plan(), resolved);
			if (frame.plan().shadowTerrain()) {
				try {
					terrain.render(frame.plan(), frame.plan().cullingMode(), frame.plan().shadowTranslucent());
					rendered = true;
				} catch (RuntimeException ignored) {
				}
			}
			if (frame.plan().shadowEntities() || frame.plan().shadowBlockEntities()) {
				try {
					features.render(frame.plan(), frame.plan().shadowEntities(), frame.plan().shadowBlockEntities());
					rendered = true;
				} catch (RuntimeException ignored) {
				}
			}
		} finally {
			try {
				if (scope != null) scope.close();
			} finally {
				bridge.clear();
			}
		}
		try {
			targets.finalizeTarget(target);
	} catch (RuntimeException ignored) {
		}
		try {
			targets.copyDepth(target);
		} catch (RuntimeException ignored) {
		}
		try {
			shadowcomp.execute(List.copyOf(resolved));
		} finally {
			bridge.clear();
		}
		return new Result(rendered, true, true, List.of("matrices", "uniforms", "targets", "programs", "render", "finalize", "copy", "shadowcomp"));
	}
}
