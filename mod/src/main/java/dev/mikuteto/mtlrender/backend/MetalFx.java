package dev.mikuteto.mtlrender.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

public final class MetalFx {
	private static final int OFF = 0;
	private static final int SPATIAL = 1;
	private static final int TEMPORAL = 2;
	private static final int DECISION_FRAMES = 30;

	private static TextureTarget input;
	private static TextureTarget previousInput;
	private static RenderTarget output;
	private static int inputWidth;
	private static int inputHeight;
	private static int outputWidth;
	private static int outputHeight;
	private static int mode;
	private static int below;
	private static int above;
	private static long lastFrameNanos;
	private static int sequence;
	private static float jitterX;
	private static float jitterY;
	private static float[] currentInverse = identity();
	private static float[] currentClip = identity();
	private static float[] previousClip = identity();
	private static float[] currentView = identity();
	private static float[] currentProjection = identity();
	private static boolean hasPrevious;
	private static boolean sceneCut;
	private static long compositionGeneration;
	private static Composition composition = new Composition(null, 0, 0, false, 0L);
	private static float deltaTime = 1.0f / 60.0f;
	private static float farPlane;
	private static float fieldOfView;

	private MetalFx() {
	}

	public record Composition(RenderTarget nativeTarget, int width, int height, boolean active, long generation) {
	}

	public static Composition composition() {
		return composition;
	}

	public static RenderTarget begin(GameRenderer renderer, RenderTarget current) {
		int previousMode = mode;
		if (AbSwitch.NAME.equals("metalfx") && !AbSwitch.b()) {
			mode = OFF;
			output = null;
			hasPrevious = false;
			if (previousMode != OFF) {
				compositionGeneration++;
			}
			publishNoOp();
			return current;
		}
		int selected = selectMode();
		if (selected == OFF || !MetalAccess.active()) {
			mode = OFF;
			output = null;
			hasPrevious = false;
			if (previousMode != OFF) {
				compositionGeneration++;
			}
			publishNoOp();
			return current;
		}
		if (previousMode != selected || output != current) {
			hasPrevious = false;
			sceneCut = true;
			compositionGeneration++;
		}
		int previousOutputWidth = outputWidth;
		int previousOutputHeight = outputHeight;
		output = current;
		outputWidth = current.width;
		outputHeight = current.height;
		if (previousOutputWidth != 0 && (previousOutputWidth != outputWidth || previousOutputHeight != outputHeight)) {
			hasPrevious = false;
			sceneCut = true;
			compositionGeneration++;
		}
		float scale = Math.max(0.5f, Math.min(1.0f, Config.get().metalfx.scale));
		int width = Math.max(1, Math.round(outputWidth * scale));
		int height = Math.max(1, Math.round(outputHeight * scale));
		if (input == null) {
			input = new TextureTarget("MetalFX input", width, height, true, GpuFormat.RGBA8_UNORM);
			previousInput = new TextureTarget("MetalFX previous input", width, height, true, GpuFormat.RGBA8_UNORM);
		} else if (inputWidth != width || inputHeight != height) {
			input.resize(width, height);
			previousInput.resize(width, height);
			hasPrevious = false;
			sceneCut = true;
			compositionGeneration++;
		}
		inputWidth = width;
		inputHeight = height;
		mode = selected;
		composition = new Composition(output, outputWidth, outputHeight, true, compositionGeneration);
		if (mode == TEMPORAL) {
			jitterX = halton(++sequence, 2) - 0.5f;
			jitterY = halton(sequence, 3) - 0.5f;
		} else {
			jitterX = 0.0f;
			jitterY = 0.0f;
		}
		return input;
	}

	public static Matrix4f projection(GameRenderer renderer, Matrix4f projection) {
		if (mode != TEMPORAL || inputWidth == 0 || inputHeight == 0) {
			return projection;
		}
		projection.m20(projection.m20() + 2.0f * jitterX / inputWidth);
		projection.m21(projection.m21() + 2.0f * jitterY / inputHeight);
		Matrix4f view = new Matrix4f(renderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix);
		Matrix4f clip = new Matrix4f(projection).mul(view);
		currentView = view.get(new float[16]);
		currentProjection = new Matrix4f(projection).get(new float[16]);
		farPlane = renderer.gameRenderState().levelRenderState.cameraRenderState.depthFar;
		fieldOfView = renderer.gameRenderState().optionsRenderState.fov;
		currentClip = clip.get(new float[16]);
		currentInverse = clip.invert().get(new float[16]);
		return projection;
	}

	public static void finish3d(RenderTarget current) {
		if (mode == OFF) {
			return;
		}
		if (output == null || current != input || !MetalAccess.active()) {
			abortComposition();
			return;
		}
		GpuTextureView color = input.getColorTextureView();
		GpuTextureView depth = input.getDepthTextureView();
		GpuTextureView target = output.getColorTextureView();
		if (color == null || depth == null || target == null) {
			abortComposition();
			return;
		}
		if (mode == 3) {
			GpuTextureView previousColor = hasPrevious ? previousInput.getColorTextureView() : color;
			if (previousColor == null) {
				hasPrevious = false;
				return;
			}
			MetalAccess.metalFxFrameInterpolation(
				color, previousColor, depth, null, target, inputWidth, inputHeight, outputWidth, outputHeight, deltaTime,
				1.0f, 1.0f, 0.05f, farPlane, fieldOfView, (float) outputWidth / outputHeight, currentView, currentProjection,
				jitterX, jitterY, sceneCut || !hasPrevious, false, true
			);
			TextureTarget completed = input;
			input = previousInput;
			previousInput = completed;
			previousClip = currentClip;
			hasPrevious = true;
			sceneCut = false;
			return;
		}
		float[] previous = hasPrevious ? previousClip : currentClip;
		MetalAccess.metalFx(
			mode, color, depth, target, inputWidth, inputHeight, outputWidth, outputHeight, jitterX, jitterY, currentInverse, previous, !hasPrevious
		);
		previousClip = currentClip;
		hasPrevious = true;
		sceneCut = false;
	}

	public static RenderTarget nativeGuiTarget(RenderTarget current) {
		if (!composition.active() || composition.nativeTarget() == null) {
			return current;
		}
		return composition.nativeTarget();
	}

	public static void close() {
		if (input != null) {
			input.destroyBuffers();
			input = null;
		}
		if (previousInput != null) {
			previousInput.destroyBuffers();
			previousInput = null;
		}
		output = null;
		mode = OFF;
		hasPrevious = false;
		sceneCut = false;
		compositionGeneration++;
		publishNoOp();
	}

	private static void publishNoOp() {
		composition = new Composition(null, 0, 0, false, compositionGeneration);
	}

	private static void abortComposition() {
		hasPrevious = false;
		compositionGeneration++;
		publishNoOp();
	}

	private static int selectMode() {
		Config.MetalFx config = Config.get().metalfx;
		long now = System.nanoTime();
		long previousNanos = lastFrameNanos;
		deltaTime = previousNanos == 0 ? 1.0f / 60.0f : Math.min(0.25f, Math.max(1.0e-4f, (now - previousNanos) / 1.0e9f));
		lastFrameNanos = now;
		boolean auto = config.auto || "auto".equalsIgnoreCase(config.mode);
		if (!auto) {
			return configuredMode(config.mode);
		}
		if (previousNanos != 0) {
			double fps = 1.0e9 / Math.max(1L, now - previousNanos);
			if (fps < config.targetFps - config.hysteresis) {
				below++;
				above = 0;
			} else if (fps > config.targetFps + config.hysteresis) {
				above++;
				below = 0;
			}
			if (below >= DECISION_FRAMES) {
				mode = config.frameInterpolation && MetalAccess.metalfxSupported(3)
					? 3
					: (MetalAccess.metalfxSupported(TEMPORAL) ? TEMPORAL : SPATIAL);
				below = 0;
			} else if (above >= DECISION_FRAMES) {
				mode = OFF;
				above = 0;
			}
		}
		return mode;
	}

	private static int configuredMode(String value) {
		Config.MetalFx config = Config.get().metalfx;
		if ("spatial".equalsIgnoreCase(value) && MetalAccess.metalfxSupported(SPATIAL)) {
			return SPATIAL;
		}
		if ("temporal".equalsIgnoreCase(value) && MetalAccess.metalfxSupported(TEMPORAL)) {
			return TEMPORAL;
		}
		if (("frame_interpolation".equalsIgnoreCase(value) || "frame-interpolation".equalsIgnoreCase(value)
				|| "interpolation".equalsIgnoreCase(value))
				&& config.frameInterpolation && MetalAccess.metalfxSupported(3)) {
			return 3;
		}
		return OFF;
	}

	private static float halton(int index, int base) {
		float result = 0.0f;
		float fraction = 1.0f / base;
		int value = Math.max(1, index);
		while (value > 0) {
			result += (value % base) * fraction;
			value /= base;
			fraction /= base;
		}
		return result;
	}

	private static float[] identity() {
		float[] values = new Matrix4f().identity().get(new float[16]);
		return values;
	}
}
