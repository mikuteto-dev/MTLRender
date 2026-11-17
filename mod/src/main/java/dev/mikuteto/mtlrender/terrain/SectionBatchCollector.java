package dev.mikuteto.mtlrender.terrain;

import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.mikuteto.mtlrender.mixin.SectionCompileTaskAccess;
import dev.mikuteto.mtlrender.mixin.SectionCompilerAccess;
import dev.mikuteto.mtlrender.mixin.SectionTaskAccess;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

public final class SectionBatchCollector {
	private SectionBatchCollector() {
	}

	public record Item(
		SectionRenderDispatcher.RenderSection.SectionTask task,
		SectionBatch.Request request
	) {
	}

	public record Selection(
		List<Item> items,
		SectionRenderDispatcher.RenderSection.SectionTask nextTask,
		SectionBatch.FlushReason reason
	) {
		public Selection {
			items = List.copyOf(items);
		}
	}

	public static void requeue(SectionTaskDynamicQueue queue, SectionRenderDispatcher.RenderSection.SectionTask task) {
		queue.add(task);
	}

	public static Selection collect(
		SectionTaskDynamicQueue queue,
		Vec3 cameraPosition,
		SectionCompiler compiler,
		SectionBufferBuilderPack buffers
	) {
		Objects.requireNonNull(queue);
		Objects.requireNonNull(cameraPosition);
		Objects.requireNonNull(compiler);
		Objects.requireNonNull(buffers);
		SectionCompilerAccess compilerAccess = (SectionCompilerAccess) compiler;
		SectionBatch.Compatibility compatibility = new SectionBatch.Compatibility(
			RustMesher.tableGeneration(),
			compilerAccess.mtlrender$ambientOcclusion(),
			compilerAccess.mtlrender$cutoutLeaves(),
			compilerAccess.mtlrender$blockColors(),
			RustMesher.modelTableIdentity()
		);
		SectionBatch.Scope scope = new SectionBatch.Scope(compatibility);
		List<Item> items = new java.util.ArrayList<>(SectionBatch.MAX_ITEMS);
		SectionRenderDispatcher.RenderSection.SectionTask nextTask = null;
		SectionBatch.FlushReason reason = null;
		try {
			while (true) {
				SectionRenderDispatcher.RenderSection.SectionTask task = queue.poll(cameraPosition);
				if (task == null) {
					reason = SectionBatch.FlushReason.QUEUE_EXHAUSTION;
					break;
				}
				SectionTaskAccess state = (SectionTaskAccess) task;
				if (state.mtlrender$isCompleted().get() || state.mtlrender$isCancelled().get()) {
					reason = SectionBatch.FlushReason.CANCELLATION;
					break;
				}
				if (!(task instanceof SectionCompileTaskAccess compileTask)) {
					nextTask = task;
					reason = SectionBatch.FlushReason.INELIGIBLE_DYNAMIC_CONTENT;
					break;
				}
				BlockPos origin = task.getRenderOrigin();
				SectionPos sectionPos = SectionPos.of(origin);
				RenderSectionRegion region = compileTask.mtlrender$region();
				boolean eligible = RustMesher.eligible(sectionPos, region);
				SectionBatch.Request request = new SectionBatch.Request(
					sectionPos,
					region,
					VertexSorting.byDistance(
						(float) (cameraPosition.x - sectionPos.minBlockX()),
						(float) (cameraPosition.y - sectionPos.minBlockY()),
						(float) (cameraPosition.z - sectionPos.minBlockZ())
					),
					buffers,
					compatibility,
					eligible
				);
				if (!scope.accept(request)) {
					nextTask = task;
					reason = scope.flushReason();
					break;
				}
				items.add(new Item(task, request));
			}
			if (reason == null) {
				reason = scope.flushReason();
			}
			return new Selection(items, nextTask, reason);
		} finally {
			scope.close();
		}
	}
}
