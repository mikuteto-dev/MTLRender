package dev.mikuteto.mtlrender.pack.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ComputePlan {
	private ComputePlan() {
	}

	public static ComputePlan empty() {
		return new ComputePlan();
	}

	public enum PassKind {
		SETUP("setup"), BEGIN("begin"), SHADOW("shadow"), SHADOWCOMP("shadowcomp"), PREPARE("prepare"), DEFERRED("deferred"), COMPOSITE("composite"), FINAL("final");

		private final String name;

		PassKind(String name) {
			this.name = name;
		}

		public String sourceName() {
			return name;
		}
	}

	public record PassId(PassKind kind, int index, char variant) {
		public PassId {
			Objects.requireNonNull(kind, "kind");
			if (index < 0) {
				throw new IllegalArgumentException("pass index must be non-negative");
			}
			if (variant != 0 && (variant < 'a' || variant > 'z')) {
				throw new IllegalArgumentException("pass variant must be a-z");
			}
		}

		public static PassId base(PassKind kind) {
			return new PassId(kind, 0, (char) 0);
		}

		public static PassId numbered(PassKind kind, int index) {
			return new PassId(kind, index, (char) 0);
		}

		public static PassId variant(PassKind kind, int index, char variant) {
			return new PassId(kind, index, variant);
		}

		public String sourceName() {
			String number = index == 0 ? "" : Integer.toString(index);
			String suffix = variant == 0 ? "" : "_" + variant;
			return kind.sourceName() + number + suffix;
		}

		public static PassId parse(String sourceName) {
			Objects.requireNonNull(sourceName, "sourceName");
			for (PassKind kind : PassKind.values()) {
				if (sourceName.equals(kind.name)) {
					return base(kind);
				}
				String prefix = kind.sourceName();
				if (!sourceName.startsWith(prefix)) {
					continue;
				}
				String rest = sourceName.substring(prefix.length());
				char variant = 0;
				int underscore = rest.indexOf('_');
				if (underscore >= 0) {
					if (rest.length() != underscore + 2) {
						break;
					}
					variant = rest.charAt(underscore + 1);
					rest = rest.substring(0, underscore);
				}
				if (rest.isEmpty()) {
					return new PassId(kind, 0, variant);
				}
				if (rest.chars().allMatch(Character::isDigit)) {
					try {
						return new PassId(kind, Integer.parseInt(rest), variant);
					} catch (NumberFormatException exception) {
						throw new IllegalArgumentException("invalid pass name: " + sourceName, exception);
					}
				}
			}
			throw new IllegalArgumentException("invalid pass name: " + sourceName);
		}
	}

	public enum Access {
		READ, WRITE, READ_WRITE
	}

	public record Dimensions(int width, int height, int depth) {
		public Dimensions {
			if (width <= 0 || height <= 0 || depth <= 0) {
				throw new IllegalArgumentException("dimensions must be positive");
			}
		}

		public static Dimensions one(int width) {
			return new Dimensions(width, 1, 1);
		}

		public static Dimensions two(int width, int height) {
			return new Dimensions(width, height, 1);
		}
	}

	public record SsboSpec(String name, int slot, long sizeBytes, Access access,
			boolean relative, float scaleX, float scaleY) {
		public SsboSpec(String name, int slot, long sizeBytes, Access access) {
			this(name, slot, sizeBytes, access, false, 0.0f, 0.0f);
		}

		public SsboSpec {
			name = requireName(name, "SSBO name");
			if (slot < 0) {
				throw new IllegalArgumentException("SSBO slot must be non-negative");
			}
			if (sizeBytes <= 0) {
				throw new IllegalArgumentException("SSBO size must be positive");
			}
			Objects.requireNonNull(access, "access");
			if (!relative && (scaleX != 0.0f || scaleY != 0.0f)) {
				throw new IllegalArgumentException("absolute SSBO cannot have relative scales");
			}
			if (relative && (!Float.isFinite(scaleX) || !Float.isFinite(scaleY) || scaleX <= 0.0f || scaleY <= 0.0f)) {
				throw new IllegalArgumentException("relative SSBO scales must be positive");
			}
		}
	}

	public enum ImageDimension {
		ONE, TWO, THREE
	}

	public record CustomImageSpec(String name, int slot, ImageDimension dimension, Dimensions size, Access access,
			String format, String internalFormat, String pixelType, boolean clear, boolean relative,
			float relativeWidth, float relativeHeight) {
		public CustomImageSpec(String name, int slot, ImageDimension dimension, Dimensions size, Access access) {
			this(name, slot, dimension, size, access, "unspecified", "unspecified", "unspecified", false, false, 0.0f, 0.0f);
		}

		public CustomImageSpec {
			name = requireName(name, "image name");
			format = requireName(format, "image format");
			internalFormat = requireName(internalFormat, "image internal format");
			pixelType = requireName(pixelType, "image pixel type");
			if (slot < 0) {
				throw new IllegalArgumentException("image slot must be non-negative");
			}
			Objects.requireNonNull(dimension, "dimension");
			Objects.requireNonNull(size, "size");
			Objects.requireNonNull(access, "access");
			if ((dimension == ImageDimension.ONE && (size.height() != 1 || size.depth() != 1))
				|| (dimension == ImageDimension.TWO && size.depth() != 1)) {
				throw new IllegalArgumentException("image size does not match its dimension");
			}
			if (!relative && (relativeWidth != 0.0f || relativeHeight != 0.0f)) {
				throw new IllegalArgumentException("absolute image cannot have relative scales");
			}
			if (relative && (!Float.isFinite(relativeWidth) || !Float.isFinite(relativeHeight)
				|| relativeWidth <= 0.0f || relativeHeight <= 0.0f)) {
				throw new IllegalArgumentException("relative image scales must be positive");
			}
		}
	}

	public sealed interface DispatchSpec permits Absolute, RenderRelative, Default, ShadowRelative, Indirect {
	}

	public record Absolute(Dimensions groups) implements DispatchSpec {
		public Absolute {
			Objects.requireNonNull(groups, "groups");
		}
	}

	public record RenderRelative(int width, int height) implements DispatchSpec {
		public RenderRelative {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("render-relative dimensions must be positive");
			}
		}
	}

	public record ShadowRelative(int width, int height) implements DispatchSpec {
		public ShadowRelative {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("shadow-relative dimensions must be positive");
			}
		}
	}

	public record Default() implements DispatchSpec {
	}

	public record Indirect(String bufferName, long offsetBytes) implements DispatchSpec {
		public Indirect {
			bufferName = requireName(bufferName, "indirect buffer name");
			if (offsetBytes < 0) {
				throw new IllegalArgumentException("indirect offset must be non-negative");
			}
		}

		public String resource() {
			return bufferName;
		}

		public long offset() {
			return offsetBytes;
		}
	}

	public record ResolvedPassPlan(PassId pass, DispatchSpec dispatch, List<SsboSpec> ssbos,
			List<CustomImageSpec> images) {
		public ResolvedPassPlan {
			Objects.requireNonNull(pass, "pass");
			Objects.requireNonNull(dispatch, "dispatch");
			ssbos = List.copyOf(Objects.requireNonNull(ssbos, "ssbos"));
			images = List.copyOf(Objects.requireNonNull(images, "images"));
			validateSsboDeclarations(ssbos);
			validateImageDeclarations(images);
		}
	}

	private static void validateSsboDeclarations(List<SsboSpec> specs) {
		Set<String> names = new HashSet<>();
		Set<Integer> slots = new HashSet<>();
		for (SsboSpec spec : specs) {
			if (!names.add(spec.name()) || !slots.add(spec.slot())) {
				throw new IllegalArgumentException("conflicting SSBO declaration: " + spec.name());
			}
		}
	}

	private static void validateImageDeclarations(List<CustomImageSpec> specs) {
		Set<String> names = new HashSet<>();
		Set<Integer> slots = new HashSet<>();
		for (CustomImageSpec spec : specs) {
			if (!names.add(spec.name()) || !slots.add(spec.slot())) {
				throw new IllegalArgumentException("conflicting image declaration: " + spec.name());
			}
		}
	}

	private static String requireName(String value, String label) {
		Objects.requireNonNull(value, label);
		if (value.isBlank()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		return value;
	}
}
