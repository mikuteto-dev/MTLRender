package dev.mikuteto.mtlrender.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.terrain.TerrainRenderer;
import dev.mikuteto.mtlrender.tune.Hardware;
import dev.mikuteto.mtlrender.tune.Tier;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public final class DebugEntryDiagnostics implements DebugScreenEntry {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("mtlrender", "diagnostics");
	private static final Identifier GROUP = ID;

	@Override
	public void display(
		DebugScreenDisplayer displayer,
		@Nullable Level serverOrClientLevel,
		@Nullable LevelChunk clientChunk,
		@Nullable LevelChunk serverChunk
	) {
		List<String> lines = new ArrayList<>();
		Hardware hardware = MetalAccess.hardware();
		if (hardware == null) {
			lines.add("MTLRender: inactive");
			lines.addAll(MemoryDiagnostics.global().diagnosticLines());
			displayer.addToGroup(GROUP, lines);
			return;
		}
		lines.add("MTLRender: " + RenderSystem.getDevice().getDeviceInfo().backendName());
		lines.add(String.format(Locale.ROOT, "GPU: %s, %d cores, %s", hardware.chip(), hardware.gpuCores(), Tier.of(hardware)));
		lines.add("Present: " + MetalAccess.presentMode());
		lines.add("GPU memory: " + MetalAccess.memoryBytes() + " B");
		TerrainRenderer terrain = TerrainRenderer.get();
		long units = MetalAccess.statTotal(MetalAccess.CULL_UNITS);
		long kept = MetalAccess.statTotal(MetalAccess.CULL_KEPT_A) + MetalAccess.statTotal(MetalAccess.CULL_KEPT_B);
		String cull = units == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", kept * 100.0 / units);
		lines.add(String.format(
			Locale.ROOT,
			"Terrain: %d sections, %d units, %d quads, cull %s (%d/%d units, %d/%d frames)",
			terrain.lastSections(), terrain.lastUnits(), terrain.lastQuads(), cull, kept, units, terrain.cullFrames(), terrain.frames()
		));
		lines.add(FeatureDiagnostics.line());
		lines.add(String.format(Locale.ROOT, "Feature native: tests %d kept %d hidden %d",
			MetalAccess.statTotal(MetalAccess.FEATURE_TESTS), MetalAccess.statTotal(MetalAccess.FEATURE_KEPT), MetalAccess.statTotal(MetalAccess.FEATURE_HIDDEN)));
		if (FrameStats.get().debugLines().isEmpty()) {
			lines.add("GPU timing: waiting");
		} else {
			lines.addAll(FrameStats.get().debugLines());
		}
		lines.addAll(MemoryDiagnostics.global().diagnosticLines());
		displayer.addToGroup(GROUP, lines);
	}
}
