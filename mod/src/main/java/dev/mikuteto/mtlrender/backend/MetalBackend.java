package dev.mikuteto.mtlrender.backend;

import java.io.IOException;
import java.nio.IntBuffer;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.mikuteto.mtlrender.Native;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.MemoryDiagnostics;
import dev.mikuteto.mtlrender.diag.Diagnostics;
import dev.mikuteto.mtlrender.sys.GameMode;
import dev.mikuteto.mtlrender.sys.Qos;
import dev.mikuteto.mtlrender.tune.Calibration;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

public final class MetalBackend implements GpuBackend {
	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void setWindowHints() {
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
	}

	@Override
	public void handleWindowCreationErrors(GLFWErrorCapture.Error error) throws BackendCreationException {
		String message = error == null ? "Failed to create window for Metal" : "GLFW_ERROR: " + error;
		throw new BackendCreationException(message, BackendCreationException.Reason.GLFW_ERROR);
	}

	@Override
	public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions, Runnable criticalShaderLoader)
		throws BackendCreationException {
		try {
			Native.load();
		} catch (IOException | UnsatisfiedLinkError e) {
			throw new BackendCreationException("MTLRender native library failed to load: " + e.getMessage(), BackendCreationException.Reason.OTHER);
		}
		NativeGpu gpu = NativeGpu.create();
		if (gpu == null) {
			throw new BackendCreationException("No Metal 4 capable Apple GPU", BackendCreationException.Reason.OTHER);
		}
		MemoryDiagnostics.global().register("metal", gpu::memorySource);
		GameMode.initialize(GameMode.Request.parse(Config.get().gameMode), gpu.hardware());
		Qos.setRenderThread();
		String deviceName = gpu.name();
		if (window != 0L) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer width = stack.mallocInt(1);
				IntBuffer height = stack.mallocInt(1);
				GLFW.glfwGetFramebufferSize(window, width, height);
				if (width.get(0) > 0 && height.get(0) > 0) {
					Calibration.start(gpu.hardware(), deviceName, width.get(0), height.get(0), gpu::calibrate);
				}
			}
		}
		Diagnostics.write(gpu.hardware(), deviceName);
		return new GpuDevice(new MetalDevice(gpu, defaultShaderSource), criticalShaderLoader);
	}
}
