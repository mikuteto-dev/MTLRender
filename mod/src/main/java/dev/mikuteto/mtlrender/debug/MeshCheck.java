package dev.mikuteto.mtlrender.debug;

import java.util.Objects;

public final class MeshCheck {
	private static final int RGB_THRESHOLD = 8;
	private static final double DIFFERENCE_LIMIT_PERCENT = 3.0;
	private static final String ENABLED_PROPERTY = "mtlrender.debug.meshCheck";
	private static final String FAIL_PROPERTY = "mtlrender.debug.meshCheck.fail";
	private static volatile Variant forcedVariant;

	private MeshCheck() {
	}

	public enum Variant {
		RASTER,
		MESH
	}

	public enum FailurePolicy {
		REPORT,
		FAIL
	}

	public record LayerCounts(long solidUnits, long cutoutUnits, long translucentUnits) {
		public LayerCounts {
			if (solidUnits < 0 || cutoutUnits < 0 || translucentUnits < 0) {
				throw new IllegalArgumentException("layer counts must not be negative");
			}
		}

		public long[] inChunkSectionLayerOrder() {
			return new long[] {solidUnits, cutoutUnits, translucentUnits};
		}
	}

	public static boolean sameLayerCounts(LayerCounts first, LayerCounts second) {
		return first != null && first.equals(second);
	}

	public record VariantState(Variant variant, LayerCounts counts) {
		public VariantState {
			Objects.requireNonNull(variant, "variant");
			Objects.requireNonNull(counts, "counts");
		}
	}

	public record ImageComparison(
		boolean enabled,
		boolean shapeMatch,
		long mismatchPixels,
		double mismatchPercent,
		int maximumChannelDifference,
		boolean outsideLimit
	) {
		private static ImageComparison disabled() {
			return new ImageComparison(false, true, 0, 0.0, 0, false);
		}
	}

	public static boolean enabled() {
		return Boolean.getBoolean(ENABLED_PROPERTY);
	}

	public static void setVariant(Variant variant) {
		forcedVariant = Objects.requireNonNull(variant, "variant");
	}

	public static void restoreVariant() {
		forcedVariant = null;
	}

	public static Variant forcedVariant() {
		return forcedVariant;
	}

	public static FailurePolicy failurePolicy() {
		return Boolean.getBoolean(FAIL_PROPERTY) ? FailurePolicy.FAIL : FailurePolicy.REPORT;
	}

	public static ImageComparison compare(int width, int height, int[] expected, int[] actual) {
		if (!enabled()) {
			return ImageComparison.disabled();
		}
		if (width < 0 || height < 0 || expected == null || actual == null) {
			return invalidShape();
		}
		long pixelCount = (long) width * height;
		boolean shapeMatch = pixelCount <= Integer.MAX_VALUE && expected.length == pixelCount && actual.length == pixelCount;
		if (!shapeMatch) {
			return invalidShape();
		}
		long mismatchPixels = 0;
		int maximum = 0;
		for (int index = 0; index < expected.length; index++) {
			int expectedPixel = expected[index];
			int actualPixel = actual[index];
			int red = Math.abs(((expectedPixel >>> 16) & 0xff) - ((actualPixel >>> 16) & 0xff));
			int green = Math.abs(((expectedPixel >>> 8) & 0xff) - ((actualPixel >>> 8) & 0xff));
			int blue = Math.abs((expectedPixel & 0xff) - (actualPixel & 0xff));
			int difference = Math.max(red, Math.max(green, blue));
			maximum = Math.max(maximum, difference);
			if (difference > RGB_THRESHOLD) {
				mismatchPixels++;
			}
		}
		double mismatchPercent = pixelCount == 0 ? 0.0 : mismatchPixels * 100.0 / pixelCount;
		return new ImageComparison(true, true, mismatchPixels, mismatchPercent, maximum, mismatchPercent > DIFFERENCE_LIMIT_PERCENT);
	}

	private static ImageComparison invalidShape() {
		return new ImageComparison(true, false, 0, 100.0, 0, true);
	}
}
