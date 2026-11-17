package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.glsl.PackShaderReflection;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ComputeDispatchPlanner {
	private static final Limits DEFAULT_LIMITS = new Limits(65_535, 65_535, 65_535);

	private ComputeDispatchPlanner() {
	}

	public static Result resolve(Program program, ShaderProperties properties, ComputePlan plan,
			PackShaderReflection.ComputeLocalSize localSize, FrameSizeContext sizes) {
		return resolve(program, properties, plan, localSize, sizes, DEFAULT_LIMITS);
	}

	public static Result resolve(Program program, ShaderProperties properties, ComputePlan plan,
			PackShaderReflection.ComputeLocalSize localSize, FrameSizeContext sizes, Limits limits) {
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(properties, "properties");
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(sizes, "sizes");
		Objects.requireNonNull(limits, "limits");
		validateSizes(sizes);
		validateLimits(limits);
		PackShaderReflection.ComputeLocalSize checkedLocal = validateLocalSize(localSize);

		Dispatch selected = selectPlan(program.name(), properties, plan);
		if (selected.indirect().isPresent()) {
			Indirect indirect = selected.indirect().orElseThrow();
			if (indirect.offset() < 0 || indirect.offset() % 4 != 0) {
				throw new IllegalArgumentException("indirect offset must be a non-negative multiple of four");
			}
			String resource = resolveIndirectResource(indirect.resource(), properties.bufferObjects());
			return new IndirectResult(resource, indirect.offset());
		}
		if (selected.groups().isPresent()) {
			GroupCount groups = selected.groups().orElseThrow();
			return direct(groups.x(), groups.y(), groups.z(), limits);
		}
		if (checkedLocal == null) {
			throw new IllegalArgumentException("compute local size is missing");
		}

		FrameSize base = isShadow(program.name()) ? sizes.shadow() : sizes.target();
		if (selected.relative().isPresent()) {
			RelativeSize relative = selected.relative().orElseThrow();
			return ceil(relative.x() * sizes.render().width(), relative.y() * sizes.render().height(), 1,
					checkedLocal, limits);
		}
		return ceil(base.width(), base.height(), 1, checkedLocal, limits);
	}

	private static Dispatch selectPlan(String name, ShaderProperties properties, ComputePlan supplied) {
		Objects.requireNonNull(supplied, "supplied");
		String base = variantBase(name);
		Map<String, String> raw = properties.raw();
		for (String candidate : base.equals(name) ? new String[] {name} : new String[] {name, base}) {
			Dispatch parsed = parse(raw, candidate);
			if (!parsed.isEmpty()) return parsed;
			ComputePlan.DispatchSpec indirect = properties.indirectDispatches().get(candidate);
			if (indirect instanceof ComputePlan.Indirect value) {
				return new Dispatch(Optional.empty(), Optional.empty(), Optional.of(new Indirect(value.resource(), value.offset())));
			}
		}
		return new Dispatch(Optional.empty(), Optional.empty(), Optional.empty());
	}

	private static Dispatch parse(Map<String, String> properties, String pass) {
		String direct = properties.get("workGroups." + pass);
		String relative = properties.get("workGroupsRender." + pass);
		String indirect = properties.get("indirect." + pass);
		return new Dispatch(parseGroups(direct), parseRelative(relative), parseIndirect(indirect));
	}

	private static Optional<GroupCount> parseGroups(String value) {
		if (value == null) return Optional.empty();
		String[] parts = value.trim().split(" +");
		if (parts.length != 3) throw new IllegalArgumentException("workGroups requires three integers");
		return Optional.of(new GroupCount(parseLong(parts[0]), parseLong(parts[1]), parseLong(parts[2])));
	}

	private static Optional<RelativeSize> parseRelative(String value) {
		if (value == null) return Optional.empty();
		String[] parts = value.trim().split(" +");
		if (parts.length != 2) throw new IllegalArgumentException("workGroupsRender requires two numbers");
		return Optional.of(new RelativeSize(parseDouble(parts[0]), parseDouble(parts[1])));
	}

	private static Optional<Indirect> parseIndirect(String value) {
		if (value == null) return Optional.empty();
		String[] parts = value.trim().split(" +");
		if (parts.length != 2) throw new IllegalArgumentException("indirect requires a resource and offset");
		return Optional.of(new Indirect(parts[0], parseLong(parts[1])));
	}

	private static long parseLong(String value) {
		try { return Long.parseLong(value); }
		catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid integer: " + value, exception); }
	}

	private static double parseDouble(String value) {
		try { return Double.parseDouble(value); }
		catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid number: " + value, exception); }
	}

	private static String variantBase(String name) {
		int separator = name.lastIndexOf('_');
		if (separator > 0 && separator == name.length() - 2) {
			char suffix = name.charAt(separator + 1);
			if (suffix >= 'a' && suffix <= 'z') {
				return name.substring(0, separator);
			}
		}
		return name;
	}

	private static String resolveIndirectResource(String value, Map<Integer, ShaderProperties.BufferObject> buffers) {
		String numeric = value.startsWith("bufferObject.") ? value.substring("bufferObject.".length()) : value;
		try {
			int index = Integer.parseInt(numeric);
			ShaderProperties.BufferObject buffer = buffers.get(index);
			if (buffer == null) {
				throw new IllegalArgumentException("indirect buffer is missing: " + value);
			}
			return buffer.name() == null ? "bufferObject." + index : buffer.name();
		} catch (NumberFormatException ignored) {
			Optional<String> match = buffers.values().stream()
				.map(ShaderProperties.BufferObject::name)
				.filter(value::equals)
				.findFirst();
			if (match.isEmpty()) {
				throw new IllegalArgumentException("indirect buffer is missing: " + value);
			}
			return match.orElseThrow();
		}
	}

	private static PackShaderReflection.ComputeLocalSize validateLocalSize(
			PackShaderReflection.ComputeLocalSize localSize) {
		if (localSize == null) {
			return null;
		}
		if (localSize.x() <= 0 || localSize.y() <= 0 || localSize.z() <= 0) {
			throw new IllegalArgumentException("compute local size must be positive");
		}
		return localSize;
	}

	private static Result ceil(double width, double height, double depth,
			PackShaderReflection.ComputeLocalSize local, Limits limits) {
		if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(depth)
				|| width <= 0 || height <= 0 || depth <= 0) {
			throw new IllegalArgumentException("dispatch dimensions must be positive and finite");
		}
		long x = ceil(width / local.x());
		long y = ceil(height / local.y());
		long z = ceil(depth / local.z());
		return direct(x, y, z, limits);
	}

	private static long ceil(double value) {
		if (value > Long.MAX_VALUE) {
			throw new IllegalArgumentException("dispatch dimension overflows");
		}
		return (long) Math.ceil(value);
	}

	private static Result direct(long x, long y, long z, Limits limits) {
		if (x <= 0 || y <= 0 || z <= 0 || x > limits.x() || y > limits.y() || z > limits.z()) {
			throw new IllegalArgumentException("dispatch group count exceeds backend limits");
		}
		return new Direct(x, y, z);
	}

	private static void validateSizes(FrameSizeContext sizes) {
		validateFrame(sizes.render());
		validateFrame(sizes.target());
		validateFrame(sizes.shadow());
	}

	private static void validateFrame(FrameSize size) {
		if (size.width() <= 0 || size.height() <= 0) {
			throw new IllegalArgumentException("frame dimensions must be positive");
		}
	}

	private static void validateLimits(Limits limits) {
		if (limits.x() <= 0 || limits.y() <= 0 || limits.z() <= 0) {
			throw new IllegalArgumentException("backend limits must be positive");
		}
	}

	private static boolean isShadow(String name) {
		return name.equals("shadow") || name.startsWith("shadow") || name.startsWith("shadowcomp");
	}

	private record Dispatch(Optional<GroupCount> groups, Optional<RelativeSize> relative,
			Optional<Indirect> indirect) {
		private boolean isEmpty() {
			return groups.isEmpty() && relative.isEmpty() && indirect.isEmpty();
		}
	}

	private record GroupCount(long x, long y, long z) {
	}

	private record RelativeSize(double x, double y) {
	}

	private record Indirect(String resource, long offset) {
	}

	public sealed interface Result permits Direct, IndirectResult {
		Kind kind();
	}

	public enum Kind { DIRECT, INDIRECT }

	public record Direct(long x, long y, long z) implements Result {
		@Override
		public Kind kind() { return Kind.DIRECT; }
	}

	public record IndirectResult(String resource, long offset) implements Result {
		@Override
		public Kind kind() { return Kind.INDIRECT; }
	}

	public record FrameSizeContext(FrameSize render, FrameSize target, FrameSize shadow) {
		public FrameSizeContext {
			render = Objects.requireNonNull(render, "render");
			target = Objects.requireNonNull(target, "target");
			shadow = Objects.requireNonNull(shadow, "shadow");
		}
	}

	public record FrameSize(long width, long height) {
	}

	public record Limits(long x, long y, long z) {
	}

}
