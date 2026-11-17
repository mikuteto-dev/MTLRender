package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RuntimeFrame {
	private final int width;
	private final int height;
	private final Viewport viewport;
	private final Scale scale;
	private final List<FlipSnapshot> flips;
	private final RenderTargetPlan targetPlan;

	public RuntimeFrame(int width, int height, Viewport viewport, Scale scale,
			List<FlipSnapshot> flips, RenderTargetPlan targetPlan) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("frame dimensions must be positive");
		}
		this.width = width;
		this.height = height;
		this.viewport = Objects.requireNonNull(viewport, "viewport");
		this.scale = Objects.requireNonNull(scale, "scale");
		this.flips = List.copyOf(Objects.requireNonNull(flips, "flips"));
		this.targetPlan = Objects.requireNonNull(targetPlan, "target plan");
	}

	public static RuntimeFrame fromTargetPlan(int width, int height, Viewport viewport, Scale scale,
			RenderTargetPlan targetPlan) {
		Objects.requireNonNull(targetPlan, "target plan");
		List<FlipSnapshot> flips = targetPlan.passes().stream()
			.map(pass -> new FlipSnapshot(pass.dimension(), pass.name(), pass.flippedBefore(), pass.flippedAfter()))
			.toList();
		return new RuntimeFrame(width, height, viewport, scale, flips, targetPlan);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public Viewport viewport() {
		return viewport;
	}

	public Scale scale() {
		return scale;
	}

	public List<FlipSnapshot> flips() {
		return flips;
	}

	public Map<String, FlipSnapshot> flipsByPass() {
		Map<String, FlipSnapshot> result = new LinkedHashMap<>();
		for (FlipSnapshot flip : flips) {
			result.put(flip.dimension() + ":" + flip.passName(), flip);
		}
		return Map.copyOf(result);
	}

	public RenderTargetPlan targetPlan() {
		return targetPlan;
	}

	public record Viewport(int x, int y, int width, int height) {
		public Viewport {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("viewport dimensions must be positive");
			}
		}
	}

	public record Scale(float x, float y) {
		public Scale {
			if (!Float.isFinite(x) || !Float.isFinite(y) || x <= 0.0f || y <= 0.0f) {
				throw new IllegalArgumentException("scale must be finite and positive");
			}
		}
	}

	public record FlipSnapshot(String dimension, String passName, Set<Integer> before, Set<Integer> after) {
		public FlipSnapshot {
			dimension = Objects.requireNonNull(dimension, "dimension");
			passName = Objects.requireNonNull(passName, "pass name");
			before = Set.copyOf(Objects.requireNonNull(before, "before flips"));
			after = Set.copyOf(Objects.requireNonNull(after, "after flips"));
		}
	}
}
