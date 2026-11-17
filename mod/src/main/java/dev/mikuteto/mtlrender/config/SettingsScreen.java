package dev.mikuteto.mtlrender.config;

import java.util.List;

import com.mojang.serialization.Codec;
import dev.mikuteto.mtlrender.terrain.OcclusionMode;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("mtlrender.options.title");
	private static final Component RESTART = Component.translatable("mtlrender.options.restartRequired");

	public SettingsScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, TITLE);
	}

	@Override
	protected void addOptions() {
		Config config = Config.get();
		this.list.addHeader(Component.translatable("mtlrender.options.section.terrain"));
		this.list.addSmall(
			booleanOption("mtlrender.options.faceCulling", config.terrain.faceCulling, value -> config.terrain.faceCulling = value),
			choiceOption("mtlrender.options.occlusion", List.of(
				OcclusionMode.AUTO.configValue(), OcclusionMode.TWO_PHASE_HIZ.configValue(), OcclusionMode.SINGLE_PHASE_HIZ.configValue(),
				OcclusionMode.CPU_BFS.configValue(), OcclusionMode.OFF.configValue()
			), config.terrain.occlusion, value -> config.terrain.occlusion = value),
			booleanOption("mtlrender.options.lod", config.terrain.lod, value -> config.terrain.lod = value),
			booleanOption("mtlrender.options.lodBeyondRenderDistance", config.terrain.lodBeyondRenderDistance, value -> config.terrain.lodBeyondRenderDistance = value),
			booleanOption("mtlrender.options.lodGenerateMissing", config.terrain.lodGenerateMissing, value -> config.terrain.lodGenerateMissing = value),
			choiceOption("mtlrender.options.lodMinRing", List.of("auto", "2", "3", "4", "5", "6", "7", "8"), config.terrain.lodMinRing, value -> config.terrain.lodMinRing = value),
			booleanOption("mtlrender.options.lodDropPlants", config.terrain.lodDropPlants, value -> config.terrain.lodDropPlants = value)
		);
		this.list.addHeader(Component.translatable("mtlrender.options.section.features"));
		this.list.addSmall(
			booleanOption("mtlrender.options.entityBatching", config.entities.batching, value -> config.entities.batching = value),
			booleanOption("mtlrender.options.particleBatching", config.particles.batching, value -> config.particles.batching = value),
			booleanOption("mtlrender.options.guiBatching", config.gui.batching, value -> config.gui.batching = value),
			booleanOption("mtlrender.options.particlePulling", config.particles.pulling, value -> config.particles.pulling = value),
			booleanOption("mtlrender.options.particleLight", config.particles.light, value -> config.particles.light = value),
			booleanOption("mtlrender.options.particleSort", config.particles.sort, value -> config.particles.sort = value),
			booleanOption("mtlrender.options.guiFrame", config.gui.frame, value -> config.gui.frame = value),
			booleanOption("mtlrender.options.glyphCache", config.gui.glyphCache, value -> config.gui.glyphCache = value),
			booleanOption("mtlrender.options.guiAtlas", config.gui.atlas, value -> config.gui.atlas = value),
			booleanOption("mtlrender.options.blockEntityBake", config.blockEntities.bake, value -> config.blockEntities.bake = value),
			booleanOption("mtlrender.options.featureHiz", config.entities.hizOcclusion, value -> config.entities.hizOcclusion = value),
			booleanOption("mtlrender.options.entityRaycast", config.entities.raycast, value -> config.entities.raycast = value),
			booleanOption("mtlrender.options.blockEntityRaycast", config.blockEntities.raycast, value -> config.blockEntities.raycast = value),
			booleanOption("mtlrender.options.weatherGpu", config.weather.gpuGeneration, value -> config.weather.gpuGeneration = value),
			booleanOption("mtlrender.options.weatherFrustum", config.weather.frustum, value -> config.weather.frustum = value)
		);
		this.list.addHeader(Component.translatable("mtlrender.options.section.shaderPacks"));
		this.list.addBig(Button.builder(Component.translatable("mtlrender.shaderpacks.open"), button ->
			this.minecraft.gui.setScreen(new ShaderPackListScreen(this, ignored -> ShaderPackOptionsScreen.open(this)))
		).build());
		this.list.addHeader(Component.translatable("mtlrender.options.section.shaderPackPrecision"));
		this.list.addSmall(
			booleanOption("mtlrender.options.packPrecision32To16", config.shaderPackPrecision.reduce32To16,
				value -> config.shaderPackPrecision.reduce32To16 = value),
			booleanOption("mtlrender.options.packPrecisionRgb11", config.shaderPackPrecision.reduceRgb11,
				value -> config.shaderPackPrecision.reduceRgb11 = value)
		);
		this.list.addHeader(Component.translatable("mtlrender.options.section.metalfx"));
		this.list.addSmall(
			booleanOption("mtlrender.options.frameInterpolation", config.metalfx.frameInterpolation,
				value -> config.metalfx.frameInterpolation = value)
		);
		this.list.addHeader(Component.translatable("mtlrender.options.section.system"));
		this.list.addSmall(
			choiceOption("mtlrender.options.gameMode", List.of("auto", "on", "off"), config.gameMode, value -> config.gameMode = value)
		);
		this.list.addHeader(Component.translatable("mtlrender.options.section.debug"));
		this.list.addSmall(
			booleanOption("mtlrender.options.overlay", config.overlay, value -> config.overlay = value),
			booleanOption("mtlrender.options.statsLog", config.statsLog, value -> config.statsLog = value)
		);
	}

	private static OptionInstance<String> choiceOption(String key, List<String> values, String current, OptionInstance.ValueUpdateListener<String> listener) {
		String initial = values.contains(current) ? current : values.getFirst();
		return new OptionInstance<>(
			key,
			OptionInstance.cachedConstantTooltip(RESTART),
			(caption, value) -> Options.genericValueLabel(caption, choiceLabel(value)),
			new OptionInstance.Enum<>(values, Codec.STRING),
			initial,
			listener
		);
	}

	private static Component choiceLabel(String value) {
		return switch (value) {
			case "auto" -> Component.translatable("mtlrender.options.auto");
			case "two_phase" -> Component.translatable("mtlrender.options.occlusion.twoPhase");
			case "single_phase" -> Component.translatable("mtlrender.options.occlusion.singlePhase");
			case "cpu_bfs" -> Component.translatable("mtlrender.options.occlusion.cpuBfs");
			case "true", "on" -> Component.translatable("options.on");
			case "false", "off" -> Component.translatable("options.off");
			default -> Component.literal(value);
		};
	}

	private static OptionInstance<Boolean> booleanOption(String key, boolean value, OptionInstance.ValueUpdateListener<? super Boolean> listener) {
		return OptionInstance.createBoolean(key, OptionInstance.cachedConstantTooltip(RESTART), value, listener);
	}

	@Override
	public void removed() {
		Config.get().save();
		super.removed();
	}
}
