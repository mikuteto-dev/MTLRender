package dev.mikuteto.mtlrender.client;

import dev.mikuteto.mtlrender.mixin.LevelExtractorAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;

public final class ClientChunkCompileScheduler {
	private ClientChunkCompileScheduler() {
	}

	public static void onChunkReady(int chunkX, int chunkZ) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			return;
		}
		ClientLevel level = minecraft.level;
		LevelExtractor extractor = minecraft.levelExtractor;
		if (level == null || extractor == null) {
			return;
		}
		LevelExtractorAccess access = (LevelExtractorAccess)(Object)extractor;
		if (access.mtlrender$level() != level) {
			return;
		}
		SectionUpdateTracker tracker = access.mtlrender$sectionUpdateTracker();
		ViewArea viewArea = minecraft.levelRenderer.viewArea();
		if (tracker == null || viewArea == null) {
			return;
		}
		RenderRegionCache cache = new RenderRegionCache();
		for (int sectionX = chunkX - 1; sectionX <= chunkX + 1; sectionX++) {
			for (int sectionZ = chunkZ - 1; sectionZ <= chunkZ + 1; sectionZ++) {
				for (int sectionY = viewArea.minSectionY(); sectionY <= viewArea.maxSectionY(); sectionY++) {
					admit(level, tracker, viewArea, cache, sectionX, sectionY, sectionZ);
				}
			}
		}
	}

	private static void admit(
		ClientLevel level,
		SectionUpdateTracker tracker,
		ViewArea viewArea,
		RenderRegionCache cache,
		int sectionX,
		int sectionY,
		int sectionZ
	) {
		long sectionNode = SectionPos.asLong(sectionX, sectionY, sectionZ);
		SectionUpdateTracker.SectionDirtyState dirtyState = tracker.getDirtyState(sectionNode);
		if (dirtyState == null || !dirtyState.isDirty()) {
			return;
		}
		SectionRenderDispatcher.RenderSection section = viewArea.getRenderSectionAt(SectionPos.of(sectionNode).center());
		if (section == null || (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && !tracker.hasAllNeighbors(level, sectionNode))) {
			return;
		}
		RenderSectionRegion region = cache.createRegion(level, sectionNode);
		section.compileAsync(region);
		dirtyState.setNotDirty();
	}
}
