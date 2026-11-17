package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferOutputDirective;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStateOverride;
import dev.mikuteto.mtlrender.pack.runtime.PackProgramCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ShadowPipelinePolicy {
	public enum Kind { DEPTH_ONLY, ALPHA_TEST, COLOR }
	public enum Failure { NONE, OPTIONAL_PROGRAM, REQUIRED_PROGRAM, INVALID_SOURCE, CACHE_GENERATION }

	public record ColorOutput(int logicalIndex, int attachmentIndex) {
		public ColorOutput {
			if (logicalIndex < 0 || logicalIndex >= 8 || attachmentIndex < 0) {
				throw new IllegalArgumentException("invalid shadow color output");
			}
		}
	}

	public record Decision(Kind kind, Failure failure, Optional<Program> program,
		List<ColorOutput> colors, Optional<GbufferStateOverride.AlphaTest> alphaTest,
		boolean depthWrite, boolean hasColorAttachment) {
		public Decision {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(failure, "failure");
			program = Objects.requireNonNull(program, "program");
			colors = List.copyOf(colors);
			alphaTest = Objects.requireNonNull(alphaTest, "alpha test");
			if (depthWrite != (failure == Failure.NONE)) throw new IllegalArgumentException("invalid depth state");
			if (hasColorAttachment != !colors.isEmpty()) throw new IllegalArgumentException("invalid color state");
		}

		public boolean usable() { return failure == Failure.NONE; }
		public boolean skip() { return failure == Failure.OPTIONAL_PROGRAM; }
	}

	public PackPipelineDesc adapt(PackPipelineDesc base, Decision decision) {
		Objects.requireNonNull(base, "base descriptor");
		Objects.requireNonNull(decision, "decision");
		if (!decision.usable()) throw new IllegalArgumentException("cannot adapt a failed shadow decision");
		List<com.mojang.blaze3d.pipeline.ColorTargetState> colors = decision.kind() == Kind.DEPTH_ONLY
			? List.of() : base.colorAttachments();
		return new PackPipelineDesc(base.vertexMsl(), base.vertexEntry(),
			decision.kind() == Kind.DEPTH_ONLY ? null : base.fragmentMsl(),
			decision.kind() == Kind.DEPTH_ONLY ? null : base.fragmentEntry(), base.bindings(), base.vertexLayout(),
			colors, base.depthFormat(), base.depthTest(), decision.depthWrite(), base.cullMode(),
			base.functionConstants(), base.layerInstancing());
	}

	public Decision choose(ShadowProgramSelection selection, ShadowTargetPlan targets,
		Optional<Reflection> fragmentReflection, Optional<GbufferStateOverride.AlphaTest> alphaTest) {
		return choose(selection, targets, fragmentReflection, alphaTest, null, -1L);
	}

	public Decision choose(ShadowProgramSelection selection, ShadowTargetPlan targets,
		Optional<Reflection> fragmentReflection, Optional<GbufferStateOverride.AlphaTest> alphaTest,
		PackProgramCache<?>.Generation generation, long expectedGeneration) {
		Objects.requireNonNull(selection, "selection");
		Objects.requireNonNull(targets, "targets");
		fragmentReflection = Objects.requireNonNull(fragmentReflection, "fragment reflection");
		alphaTest = Objects.requireNonNull(alphaTest, "alpha test");
		if (generation != null && (!generation.current() || generation.identity().generation() != expectedGeneration)) {
			return failed(Failure.CACHE_GENERATION);
		}
		if (selection.program().isEmpty()) {
			return failed(selection.optional() || selection.disabled() ? Failure.OPTIONAL_PROGRAM : Failure.REQUIRED_PROGRAM);
		}
		Program program = selection.program().orElseThrow();
		if (!validRenderSource(program)) return failed(Failure.INVALID_SOURCE);
		Optional<String> fragment = program.source(Program.Stage.FRAGMENT);
		if (fragment.isPresent()) {
			try {
				GbufferOutputDirective.find(fragment.orElseThrow());
			} catch (RuntimeException exception) {
				return failed(Failure.INVALID_SOURCE);
			}
		}
		if (alphaTest.isPresent() && fragment.isEmpty()) return failed(Failure.INVALID_SOURCE);
		List<ColorOutput> colors;
		try {
			colors = outputs(targets, fragmentReflection);
		} catch (RuntimeException exception) {
			return failed(Failure.INVALID_SOURCE);
		}
		Kind kind = alphaTest.isPresent() ? Kind.ALPHA_TEST : colors.isEmpty() ? Kind.DEPTH_ONLY : Kind.COLOR;
		return new Decision(kind, Failure.NONE, Optional.of(program), colors, alphaTest,
			true, !colors.isEmpty());
	}

	private static List<ColorOutput> outputs(ShadowTargetPlan targets, Optional<Reflection> reflection) {
		if (reflection.isEmpty()) return List.of();
		List<ColorOutput> result = new ArrayList<>();
		for (Reflection.FragmentOutput output : reflection.orElseThrow().fragmentOutputs()) {
			int logical = output.colortex();
			if (logical < 0 || logical >= 8) throw new IllegalArgumentException("shadow color output is out of range");
			if (!targets.target("shadowcolor" + logical).present()) continue;
			result.add(new ColorOutput(logical, logical));
		}
		return List.copyOf(result);
	}

	private static boolean validRenderSource(Program program) {
		if (program.source(Program.Stage.COMPUTE).isPresent()) return false;
		if (program.source(Program.Stage.VERTEX).isEmpty()) return false;
		if (program.source(Program.Stage.TESS_CONTROL).isPresent()
			!= program.source(Program.Stage.TESS_EVALUATION).isPresent()) return false;
		return !program.source(Program.Stage.TESS_CONTROL).isPresent() || program.source(Program.Stage.FRAGMENT).isPresent();
	}

	private static Decision failed(Failure failure) {
		return new Decision(Kind.DEPTH_ONLY, failure, Optional.empty(), List.of(), Optional.empty(), false, false);
	}
}
