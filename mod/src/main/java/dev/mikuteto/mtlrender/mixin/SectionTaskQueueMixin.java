package dev.mikuteto.mtlrender.mixin;

import java.util.List;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SectionTaskDynamicQueue.class)
abstract class SectionTaskQueueMixin {
	@Shadow
	@Final
	private List<SectionRenderDispatcher.RenderSection.SectionTask> tasks;

	@Overwrite
	public synchronized void add(final SectionRenderDispatcher.RenderSection.SectionTask task) {
		SectionTaskAccess state = (SectionTaskAccess) (Object) task;
		if (!state.mtlrender$isCancelled().get() && !state.mtlrender$isCompleted().get()) {
			this.tasks.add(task);
		}
	}

	@Overwrite
	public synchronized SectionRenderDispatcher.RenderSection.SectionTask poll(final Vec3 cameraPos) {
		for (int index = this.tasks.size() - 1; index >= 0; index--) {
			SectionRenderDispatcher.RenderSection.SectionTask task = this.tasks.get(index);
			SectionTaskAccess state = (SectionTaskAccess) (Object) task;
			if (state.mtlrender$isCancelled().get() || state.mtlrender$isCompleted().get()) {
				this.tasks.remove(index);
			}
		}

		int bestIndex = -1;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (int index = 0; index < this.tasks.size(); index++) {
			SectionRenderDispatcher.RenderSection.SectionTask task = this.tasks.get(index);
			double distance = task.getRenderOrigin().distToCenterSqr(cameraPos);
			if (bestIndex < 0 || distance < bestDistance) {
				bestIndex = index;
				bestDistance = distance;
			}
		}
		return bestIndex < 0 ? null : this.tasks.remove(bestIndex);
	}

	@Overwrite
	public synchronized void clear() {
		for (int index = 0; index < this.tasks.size(); index++) {
			this.tasks.get(index).cancel();
		}
		this.tasks.clear();
	}
}
