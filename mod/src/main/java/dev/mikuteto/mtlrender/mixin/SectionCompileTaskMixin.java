package dev.mikuteto.mtlrender.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.mikuteto.mtlrender.client.SectionCompileAllocationPool;
import dev.mikuteto.mtlrender.client.SectionCompileRegionLeases;
import dev.mikuteto.mtlrender.debug.ChunkTiming;
import dev.mikuteto.mtlrender.pathtracing.VoxelSectionStateCapture;
import dev.mikuteto.mtlrender.terrain.MeshOwnership;
import dev.mikuteto.mtlrender.terrain.RustMesher;
import dev.mikuteto.mtlrender.terrain.RustSectionCompiler;
import dev.mikuteto.mtlrender.terrain.SectionBatch;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import com.mojang.logging.LogUtils;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class SectionCompileTaskMixin {
	private static final Logger LOGGER = LogUtils.getLogger();
	@org.spongepowered.asm.mixin.Unique
	private long mtlrender$compileStartNanos;
	@org.spongepowered.asm.mixin.Unique
	private SectionCompileAllocationPool.WorkLease mtlrender$regionLease;
	@org.spongepowered.asm.mixin.Unique
	private MeshOwnership.Token mtlrender$ownership;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mtlrender$claimRegion(RenderSectionRegion region, boolean isRecompile, CallbackInfo info) {
		mtlrender$regionLease = SectionCompileRegionLeases.claim(region);
		SectionRenderDispatcher.RenderSection.SectionTask task =
			(SectionRenderDispatcher.RenderSection.SectionTask) (Object) this;
		mtlrender$ownership = MeshOwnership.begin(task.getRenderOrigin().asLong());
	}

	@Inject(method = "doTask", at = @At("HEAD"))
	private void mtlrender$startCompileTiming(SectionBufferBuilderPack buffers, CallbackInfoReturnable<?> info) {
		MeshOwnership.enter(mtlrender$ownership);
		if (ChunkTiming.enabled()) {
			mtlrender$compileStartNanos = System.nanoTime();
		}
	}

	@Inject(method = "doTask", at = @At("RETURN"))
	private void mtlrender$finishCompileTiming(SectionBufferBuilderPack buffers, CallbackInfoReturnable<?> info) {
		if (ChunkTiming.enabled() && mtlrender$compileStartNanos != 0L) {
			SectionTaskTimingAccess timing = (SectionTaskTimingAccess) (Object) this;
			ChunkTiming.recordSectionDuration(
				ChunkTiming.Stage.SECTION_COMPILE_TOTAL,
				timing.mtlrender$sectionKey(),
				System.nanoTime() - mtlrender$compileStartNanos
			);
			mtlrender$compileStartNanos = 0L;
		}
		MeshOwnership.leave();
	}

	@WrapMethod(method = "doTask")
	private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult mtlrender$releaseRegion(
		SectionBufferBuilderPack buffers,
		Operation<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> original
	) {
		try {
			return original.call(buffers);
		} finally {
			SectionCompileRegionLeases.release(mtlrender$regionLease);
			mtlrender$regionLease = null;
		}
	}

	@Inject(method = "cancel", at = @At("HEAD"))
	private void mtlrender$releaseCancelledRegion(CallbackInfo info) {
		if (mtlrender$ownership != null) {
			MeshOwnership.invalidate(mtlrender$ownership);
		}
		SectionCompileRegionLeases.release(mtlrender$regionLease);
		mtlrender$regionLease = null;
	}

	@Redirect(
		method = "doTask",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"
		)
	)
	private SectionCompiler.Results mtlrender$compile(
		SectionCompiler compiler,
		SectionPos sectionPos,
		RenderSectionRegion region,
		VertexSorting vertexSorting,
		SectionBufferBuilderPack buffers
	) {
		boolean check = Boolean.getBoolean("mtlrender.debug.mesherCheck");
		boolean rustEnabled = RustMesher.enabled();
		boolean packMetadata = RustMesher.packRequirements().needsExtraData();
		SectionBatch.Scope batchScope = rustEnabled ? SectionBatch.Scope.current() : null;
		SectionCompiler.Results selected;
		if (!rustEnabled && !check && !packMetadata) {
			selected = mtlrender$compileVanilla(compiler, sectionPos, region, vertexSorting, buffers);
		} else {
			SectionCompiler.Results rust = RustSectionCompiler.compile(
				compiler, sectionPos, region, vertexSorting, buffers, batchScope
			);
			if (!check) {
				selected = rust;
			} else {
				SectionCompiler.Results vanilla = mtlrender$compileVanilla(
					compiler, sectionPos, region, vertexSorting, buffers
				);
				String mismatch = RustSectionCompiler.mismatch(rust, vanilla);
				if (mismatch == null) {
					vanilla.release();
					selected = rust;
				} else {
					if (ChunkTiming.enabled()) {
						ChunkTiming.recordMesherFailedItem();
						ChunkTiming.recordMesherMismatch();
					}
					RustMesher.disable();
					LOGGER.error("Rust mesher mismatch at {} in {}; using vanilla section mesh", sectionPos, mismatch);
					rust.release();
					selected = vanilla;
				}
			}
		}
		VoxelSectionStateCapture.capture(selected, sectionPos, region);
		return selected;
	}

	private SectionCompiler.Results mtlrender$compileVanilla(
		SectionCompiler compiler,
		SectionPos sectionPos,
		RenderSectionRegion region,
		VertexSorting vertexSorting,
		SectionBufferBuilderPack buffers
	) {
		long start = ChunkTiming.enabled() ? System.nanoTime() : 0L;
		try {
			return compiler.compile(sectionPos, region, vertexSorting, buffers);
		} finally {
			if (start != 0L) {
				ChunkTiming.recordSectionDuration(
					ChunkTiming.Stage.VANILLA_COMPILE,
					sectionPos.asLong(),
					System.nanoTime() - start
				);
			}
		}
	}
}
