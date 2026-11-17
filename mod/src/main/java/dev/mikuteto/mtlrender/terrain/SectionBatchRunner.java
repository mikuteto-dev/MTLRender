package dev.mikuteto.mtlrender.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import net.minecraft.CrashReport;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import dev.mikuteto.mtlrender.mixin.SectionCompilerAccess;
import dev.mikuteto.mtlrender.mixin.SectionTaskAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.world.phys.Vec3;

public final class SectionBatchRunner implements Runnable {
	private final SectionTaskDynamicQueue queue;
	private final AtomicReference<Vec3> cameraPosition;
	private final SectionCompiler compiler;
	private final SectionBufferBuilderPack fixedBuffers;
	private final SectionBufferBuilderPool bufferPool;
	private final TracingExecutor executor;
	private final BooleanSupplier closed;

	public SectionBatchRunner(
		SectionTaskDynamicQueue queue,
		AtomicReference<Vec3> cameraPosition,
		SectionCompiler compiler,
		SectionBufferBuilderPack fixedBuffers,
		SectionBufferBuilderPool bufferPool,
		TracingExecutor executor,
		BooleanSupplier closed
	) {
		this.queue = Objects.requireNonNull(queue);
		this.cameraPosition = Objects.requireNonNull(cameraPosition);
		this.compiler = Objects.requireNonNull(compiler);
		this.fixedBuffers = Objects.requireNonNull(fixedBuffers);
		this.bufferPool = Objects.requireNonNull(bufferPool);
		this.executor = Objects.requireNonNull(executor);
		this.closed = Objects.requireNonNull(closed);
	}

	@Override
	public void run() {
		if (this.closed.getAsBoolean()) {
			return;
		}
		SectionBatchCollector.Selection selection = SectionBatchCollector.collect(
			this.queue, this.cameraPosition.get(), this.compiler, this.fixedBuffers
		);
		if (selection.items().isEmpty() && selection.nextTask() == null) {
			return;
		}
		List<RustMesher.PreparedRequest> prepared = prepare(selection.items());
		boolean continueScheduling;
		try {
			continueScheduling = executePrepared(selection.items(), prepared);
		} finally {
			for (RustMesher.PreparedRequest request : prepared) {
				if (request != null) {
					request.close();
				}
			}
		}
		if (continueScheduling && selection.nextTask() != null) {
			continueScheduling = executeVanilla(selection.nextTask());
		}
		if (continueScheduling && !this.closed.getAsBoolean()) {
			this.executor.execute(this);
		}
	}

	private List<RustMesher.PreparedRequest> prepare(List<SectionBatchCollector.Item> items) {
		List<RustMesher.PreparedRequest> prepared = new ArrayList<>(items.size());
		SectionCompilerAccess access = (SectionCompilerAccess) this.compiler;
		for (SectionBatchCollector.Item item : items) {
			SectionBatch.Request request = item.request();
			prepared.add(RustMesher.prepare(
				request.sectionPos(), request.region(), access.mtlrender$ambientOcclusion(),
				access.mtlrender$cutoutLeaves(), access.mtlrender$blockColors()
			));
		}
		return prepared;
	}

	private boolean executePrepared(List<SectionBatchCollector.Item> items, List<RustMesher.PreparedRequest> prepared) {
		RustMesher.BatchResult[] results;
		boolean batchFailed = false;
		try {
			results = RustMesher.meshSections(prepared.toArray(RustMesher.PreparedRequest[]::new));
		} catch (RuntimeException failure) {
			batchFailed = true;
			results = new RustMesher.BatchResult[prepared.size()];
			if (ChunkTiming.enabled()) {
				for (int index = 0; index < items.size(); index++) {
					ChunkTiming.recordMesherFallback();
					ChunkTiming.recordMesherFailedItem();
				}
			}
		}
		for (int index = 0; index < items.size(); index++) {
			SectionBatchCollector.Item item = items.get(index);
			RustMesher.BatchResult nativeResult = results[index];
			if (!batchFailed && (nativeResult == null || !nativeResult.success()) && ChunkTiming.enabled()) {
				ChunkTiming.recordMesherFallback();
			}
			SectionBufferBuilderPack buffers = this.bufferPool.acquire();
			if (buffers == null) {
				SectionBatchCollector.requeue(this.queue, item.task());
				for (int remaining = index + 1; remaining < items.size(); remaining++) {
					SectionBatchCollector.requeue(this.queue, items.get(remaining).task());
				}
				return false;
			}
			SectionBatch.Scope scope = new SectionBatch.Scope(item.request().compatibility());
			try {
				if (nativeResult != null && nativeResult.success()) {
					try {
						SectionCompiler.Results value = RustSectionCompiler.batchResults(nativeResult.output(), buffers);
						scope.install(item.request(), value);
					} catch (RuntimeException failure) {
						scope.flush(SectionBatch.FlushReason.NATIVE_FAILURE);
					}
				}
				if (!execute(item.task(), buffers, scope)) {
					for (int remaining = index + 1; remaining < items.size(); remaining++) {
						SectionBatchCollector.requeue(this.queue, items.get(remaining).task());
					}
					return false;
				}
			} finally {
				scope.close();
				RustMesher.PreparedRequest request = prepared.get(index);
				if (request != null) {
					request.close();
				}
			}
		}
		return true;
	}

	private boolean executeVanilla(SectionRenderDispatcher.RenderSection.SectionTask task) {
		SectionBufferBuilderPack buffer = this.bufferPool.acquire();
		if (buffer == null) {
			SectionBatchCollector.requeue(this.queue, task);
			return false;
		}
		return execute(task, buffer, null);
	}

	private boolean execute(
		SectionRenderDispatcher.RenderSection.SectionTask task,
		SectionBufferBuilderPack buffer,
		SectionBatch.Scope scope
	) {
		try (SectionBatch.Scope.Handoff ignored = scope == null ? null : scope.bind()) {
			SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult result = task.doTask(buffer);
			((SectionTaskAccess) task).mtlrender$isCompleted().set(true);
			if (result == SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.SUCCESSFUL) {
				buffer.clearAll();
			} else {
				buffer.discardAll();
			}
			this.bufferPool.release(buffer);
			return true;
		} catch (NullPointerException failure) {
			SectionBatchCollector.requeue(this.queue, task);
			return false;
		} catch (Exception failure) {
			Minecraft.getInstance().delayCrash(CrashReport.forThrowable(failure, "Batching sections"));
			return false;
		}
	}
}
