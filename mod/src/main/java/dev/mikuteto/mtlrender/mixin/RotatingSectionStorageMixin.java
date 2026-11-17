package dev.mikuteto.mtlrender.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RotatingSectionStorage.class)
abstract class RotatingSectionStorageMixin {
	private static final Logger LOGGER = LogUtils.getLogger();

	@Shadow
	private SectionPos centerSectionPos;

	@Shadow
	@Final
	private int radius;

	@Shadow
	@Final
	private int minY;

	@Shadow
	@Final
	private int sectionGridSizeY;

	@Shadow
	@Final
	private int sectionGridSizeXZ;

	@Shadow
	public abstract RotatingSectionStorage.Value getValue(int sectionX, int sectionY, int sectionZ);

	@Inject(method = "repositionCenter", at = @At("HEAD"), cancellable = true)
	private void mtlrender$repositionCenter(SectionPos newCenterSectionPos, CallbackInfoReturnable<Boolean> cir) {
		SectionPos oldCenter = this.centerSectionPos;
		if (newCenterSectionPos.equals(oldCenter)) {
			cir.setReturnValue(false);
			return;
		}

		if (oldCenter == null) {
			return;
		}

		int gridSize = this.sectionGridSizeXZ;
		long deltaX = Math.abs((long) newCenterSectionPos.x() - (long) oldCenter.x());
		long deltaZ = Math.abs((long) newCenterSectionPos.z() - (long) oldCenter.z());
		if (deltaX >= gridSize || deltaZ >= gridSize || newCenterSectionPos.y() != oldCenter.y()) {
			return;
		}

		int oldLowestX = oldCenter.x() - this.radius;
		int oldLowestZ = oldCenter.z() - this.radius;
		int newLowestX = newCenterSectionPos.x() - this.radius;
		int newLowestZ = newCenterSectionPos.z() - this.radius;

		for (int gridX = 0; gridX < gridSize; gridX++) {
			int newSectionX = newLowestX + Math.floorMod(gridX - newLowestX, gridSize);
			int oldSectionX = oldLowestX + Math.floorMod(gridX - oldLowestX, gridSize);
			boolean columnChanged = newSectionX != oldSectionX;

			for (int gridZ = 0; gridZ < gridSize; gridZ++) {
				int newSectionZ = newLowestZ + Math.floorMod(gridZ - newLowestZ, gridSize);
				int oldSectionZ = oldLowestZ + Math.floorMod(gridZ - oldLowestZ, gridSize);

				if (!columnChanged && newSectionZ == oldSectionZ) {
					continue;
				}

				for (int gridY = 0; gridY < this.sectionGridSizeY; gridY++) {
					int sectionY = this.minY + gridY;
					RotatingSectionStorage.Value value = this.getValue(oldSectionX, sectionY, oldSectionZ);
					if (value == null) {
						continue;
					}

					long target = SectionPos.asLong(newSectionX, sectionY, newSectionZ);
					if (value.getSectionNode() != target) {
						value.setSectionNode(target);
					}
				}
			}
		}

		this.centerSectionPos = newCenterSectionPos;

		if (Boolean.getBoolean("mtlrender.debug.repositionCheck")) {
			this.mtlrender$verifyReposition(newCenterSectionPos);
		}

		cir.setReturnValue(true);
		cir.cancel();
	}

	private void mtlrender$verifyReposition(SectionPos center) {
		int gridSize = this.sectionGridSizeXZ;
		int lowestX = center.x() - this.radius;
		int lowestZ = center.z() - this.radius;
		int mismatches = 0;

		for (int gridX = 0; gridX < gridSize; gridX++) {
			int sectionX = lowestX + Math.floorMod(gridX - lowestX, gridSize);

			for (int gridZ = 0; gridZ < gridSize; gridZ++) {
				int sectionZ = lowestZ + Math.floorMod(gridZ - lowestZ, gridSize);

				for (int gridY = 0; gridY < this.sectionGridSizeY; gridY++) {
					int sectionY = this.minY + gridY;
					RotatingSectionStorage.Value value = this.getValue(sectionX, sectionY, sectionZ);
					long target = SectionPos.asLong(sectionX, sectionY, sectionZ);
					if (value == null || value.getSectionNode() != target) {
						mismatches++;
					}
				}
			}
		}

		if (mismatches > 0) {
			LOGGER.warn(
				"Incremental section storage reposition mismatch: {} of {} cells",
				mismatches,
				gridSize * gridSize * this.sectionGridSizeY
			);
		}
	}
}
