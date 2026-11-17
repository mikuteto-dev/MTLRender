package dev.mikuteto.mtlrender.pack.gbuffer;

import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;

import java.util.List;
import java.util.Optional;

public final class GbufferOutputLayout {
	private static final List<String> LEGACY_NAMES = List.of(
		"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"
	);
	private final List<Output> outputs;
	private final boolean explicit;

	private GbufferOutputLayout(List<Output> outputs, boolean explicit) {
		this.outputs = List.copyOf(outputs);
		this.explicit = explicit;
	}

	public static GbufferOutputLayout vanilla() {
		return new GbufferOutputLayout(List.of(new Output(0, Optional.of("colortex0"))), false);
	}

	public static GbufferOutputLayout from(String fragmentSource, ShaderProperties properties, RenderTargetPlan plan) {
		return GbufferOutputDirective.find(fragmentSource)
			.map(directive -> from(directive, properties, plan))
			.orElseGet(GbufferOutputLayout::vanilla);
	}

	public static GbufferOutputLayout from(GbufferOutputDirective directive, ShaderProperties properties,
			RenderTargetPlan plan) {
		List<Output> outputs = directive.outputs().stream().map(slot -> {
			if (!slot.present()) return new Output(outputsSizePlaceholder(), Optional.empty());
			int index = slot.value();
			return new Output(index, Optional.of(plan.color(index).name()));
		}).toList();
		return new GbufferOutputLayout(withLocations(outputs), true);
	}

	private static int outputsSizePlaceholder() {
		return -1;
	}

	private static List<Output> withLocations(List<Output> outputs) {
		return java.util.stream.IntStream.range(0, outputs.size())
			.mapToObj(index -> new Output(index, outputs.get(index).targetName()))
		.toList();
	}

	public List<Output> outputs() {
		return outputs;
	}

	public boolean explicit() {
		return explicit;
	}

	public boolean writes(int location) {
		return location >= 0 && location < outputs.size() && outputs.get(location).targetName().isPresent();
	}

	public static Optional<String> canonicalTarget(String name) {
		if (name.startsWith("colortex")) {
			try {
				int index = Integer.parseInt(name.substring("colortex".length()));
				return index >= 0 && index < 16 ? Optional.of("colortex" + index) : Optional.empty();
			} catch (NumberFormatException exception) {
				return Optional.empty();
			}
		}
		for (int index = 0; index < LEGACY_NAMES.size(); index++) {
			if (LEGACY_NAMES.get(index).equals(name)) return Optional.of("colortex" + index);
		}
		return Optional.empty();
	}

	public record Output(int location, Optional<String> targetName) {
		public Output {
			targetName = targetName == null ? Optional.empty() : targetName;
		}
	}
}
