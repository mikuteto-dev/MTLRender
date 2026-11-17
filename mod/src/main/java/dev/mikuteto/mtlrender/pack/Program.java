package dev.mikuteto.mtlrender.pack;

import dev.mikuteto.mtlrender.pack.gbuffer.GbufferOutputLayout;

import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public final class Program {
	public enum Stage {
		VERTEX(".vsh"),
		GEOMETRY(".gsh"),
		TESS_CONTROL(".tcs"),
		TESS_EVALUATION(".tes"),
		FRAGMENT(".fsh"),
		COMPUTE(".csh");

		private final String extension;

		Stage(String extension) {
			this.extension = extension;
		}

		public String extension() {
			return extension;
		}
	}

	private final String name;
	private final Map<Stage, String> sources;
	private final GbufferOutputLayout outputLayout;

	public Program(String name, Map<Stage, String> sources) {
		this(name, sources, GbufferOutputLayout.vanilla());
	}

	private Program(String name, Map<Stage, String> sources, GbufferOutputLayout outputLayout) {
		this.name = name;
		this.sources = Map.copyOf(sources);
		this.outputLayout = outputLayout;
	}

	public String name() {
		return name;
	}

	public Map<Stage, String> sources() {
		return sources;
	}

	public Optional<String> source(Stage stage) {
		return Optional.ofNullable(sources.get(stage));
	}

	public GbufferOutputLayout outputLayout() {
		return outputLayout;
	}

	public Program withOutputLayout(GbufferOutputLayout layout) {
		return new Program(name, sources, layout);
	}

	public boolean isRenderProgram() {
		return isOrdinaryRenderProgram() || isTessellatedRenderProgram();
	}

	public boolean isOrdinaryRenderProgram() {
		return has(Stage.VERTEX) && has(Stage.FRAGMENT)
			&& !has(Stage.TESS_CONTROL) && !has(Stage.TESS_EVALUATION);
	}

	public boolean hasCompleteTessellationPair() {
		return has(Stage.TESS_CONTROL) && has(Stage.TESS_EVALUATION);
	}

	public boolean isTessellatedRenderProgram() {
		return has(Stage.VERTEX) && hasCompleteTessellationPair() && has(Stage.FRAGMENT);
	}

	public Optional<String> renderValidationError() {
		boolean control = has(Stage.TESS_CONTROL);
		boolean evaluation = has(Stage.TESS_EVALUATION);
		if (control != evaluation) {
			return Optional.of("tessellation control and evaluation stages must be present together");
		}
		if (control && (!has(Stage.VERTEX) || !has(Stage.FRAGMENT))) {
			return Optional.of("tessellated render program requires vertex, tessellation-control, tessellation-evaluation, and fragment stages");
		}
		if (!has(Stage.VERTEX) || !has(Stage.FRAGMENT)) {
			return Optional.of("render program requires vertex and fragment stages");
		}
		return Optional.empty();
	}

	public boolean isComputeProgram() {
		return sources.containsKey(Stage.COMPUTE);
	}

	public boolean isValid() {
		return isRenderProgram() || isComputeProgram();
	}

	private boolean has(Stage stage) {
		return sources.containsKey(requireNonNull(stage, "stage"));
	}
}
