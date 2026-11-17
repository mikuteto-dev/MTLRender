package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.sys.Qos;
import dev.mikuteto.mtlrender.terrain.DirectUberBuffer;
import dev.mikuteto.mtlrender.terrain.MeshOwnership;
import dev.mikuteto.mtlrender.terrain.RustMesher;
import dev.mikuteto.mtlrender.terrain.SectionBatchRunner;
import dev.mikuteto.mtlrender.tune.Hardware;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.class)
abstract class SectionRenderDispatcherMixin {
	private static final int DIRECT_STAGING_SIZE = 1 << 16;

	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 1)
	private TracingExecutor mtlrender$sizeCompileExecutor(TracingExecutor ignored) {
		Hardware hardware = MetalAccess.hardware();
		int workers = hardware == null ? Hardware.mesherWorkers(0, 0) : hardware.mesherWorkers();
		return new TracingExecutor(
			Executors.newFixedThreadPool(
				workers,
				Thread.ofPlatform().daemon(true).name("MTLRender-Mesher-", 0).factory()
			)
		);
	}

	@Shadow
	@Final
	private SectionTaskDynamicQueue queue;

	@Shadow
	@Final
	private SectionBufferBuilderPack fixedBuffers;

	@Shadow
	@Final
	private SectionBufferBuilderPool bufferPool;

	@Shadow
	@Final
	private TracingExecutor executor;

	@Shadow
	@Final
	private AtomicReference<Vec3> cameraPosition;

	@Shadow
	private volatile boolean closed;

	@Shadow
	private volatile SectionCompiler sectionCompiler;

	@Inject(method = "runTask", at = @At("HEAD"))
	private void mtlrender$setWorkerQos(CallbackInfo ci) {
		Qos.setWorkerThread();
	}

	@Inject(method = "runTask", at = @At("HEAD"), cancellable = true)
	private void mtlrender$runBatch(CallbackInfo ci) {
		if (RustMesher.enabled()) {
			ci.cancel();
			new SectionBatchRunner(
				this.queue,
				this.cameraPosition,
				this.sectionCompiler,
				this.fixedBuffers,
				this.bufferPool,
				this.executor,
				() -> this.closed
			).run();
		}
	}

	@Inject(method = "dispose", at = @At("HEAD"))
	private void mtlrender$invalidateMeshOwnership(CallbackInfo ci) {
		MeshOwnership.reset();
	}

	@Shadow
	@Final
	private ReentrantLock copyLock;

	@ModifyArg(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/StagingBuffer;create(Ljava/lang/String;Lcom/mojang/blaze3d/systems/GpuDevice;I)Lcom/mojang/blaze3d/vertex/StagingBuffer;"),
		index = 2
	)
	private int mtlrender$shrinkStaging(int size) {
		return MetalAccess.active() ? DIRECT_STAGING_SIZE : size;
	}

	@Redirect(method = "lambda$new$0", at = @At(value = "NEW", target = "com/mojang/blaze3d/vertex/UberGpuBuffer"))
	private UberGpuBuffer<?> mtlrender$directUberBuffer(String name, int usage, int heapSize, int alignSize, StagingBuffer stagingBuffer) {
		return MetalAccess.active()
			? new DirectUberBuffer<>(name, usage, heapSize, alignSize, stagingBuffer, copyLock)
			: new UberGpuBuffer<>(name, usage, heapSize, alignSize, stagingBuffer);
	}
}
