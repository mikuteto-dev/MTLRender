package dev.mikuteto.mtlrender.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.PackOption;
import dev.mikuteto.mtlrender.pack.PackOptionsModel;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ShaderPackOptionsScreen extends Screen {
	private final Screen lastScreen;
	private final Screen exitScreen;
	private final PackManager manager;
	private final PackOptionsModel model;
	private final String screenName;

	private ShaderPackOptionsScreen(Screen lastScreen, Screen exitScreen, PackManager manager, PackOptionsModel model, String screenName) {
		super(Component.literal(manager.activeName()));
		this.lastScreen = lastScreen;
		this.exitScreen = exitScreen;
		this.manager = manager;
		this.model = model;
		this.screenName = screenName;
	}

	public static void open(Screen lastScreen) {
		Minecraft minecraft = Minecraft.getInstance();
		PackManager manager = PackManager.get();
		Optional<ShaderPack> active = manager.active();
		if (active.isEmpty() || manager.activeName().isEmpty()) return;
		Path path = manager.shaderpacksDirectory().resolve(manager.activeName());
		if (!Files.exists(path)) path = manager.shaderpacksDirectory().resolve(manager.activeName() + ".zip");
		try {
			PackOptionsModel model = PackOptionsModel.load(active.get(), path, active.get().optionValues());
			minecraft.gui.setScreen(new ShaderPackOptionsScreen(lastScreen, lastScreen, manager, model, ""));
		} catch (Exception ignored) {
		}
	}

	@Override
	protected void init() {
		PackOptionsModel.Screen screen = model.screen(screenName).orElse(model.root());
		int columns = Math.max(1, Math.min(4, screen.columns()));
		int gap = 8;
		int width = Math.min(200, (this.width - 40 - gap * (columns - 1)) / columns);
		int left = (this.width - (width * columns + gap * (columns - 1))) / 2;
		int y = 32;
		Button title = Button.builder(Component.literal(screen.name().isEmpty() ? manager.activeName() : screen.label()), button -> { })
			.bounds(left, 8, width * columns + gap * (columns - 1), 20).build();
		title.active = false;
		this.addRenderableWidget(title);

		int column = 0;
		for (PackOptionsModel.ScreenEntry entry : screen.entries()) {
			if (entry.kind() == PackOptionsModel.Kind.SPACER) {
				column = (column + 1) % columns;
				if (column == 0) y += 28;
				continue;
			}
			if (entry.kind() == PackOptionsModel.Kind.UNKNOWN) continue;
			int x = left + column * (width + gap);
			if (entry.kind() == PackOptionsModel.Kind.SCREEN) {
				String child = entry.token().substring(1, entry.token().length() - 1);
				this.addRenderableWidget(Button.builder(Component.literal("[" + entry.label() + "]"), button ->
					this.minecraft.gui.setScreen(new ShaderPackOptionsScreen(this, exitScreen, manager, model, child)))
					.bounds(x, y, width, 20).build());
			} else if (entry.kind() == PackOptionsModel.Kind.PROFILE) {
				addProfile(x, y, width, entry.label());
			} else if (entry.kind() == PackOptionsModel.Kind.OPTION) {
				PackOption option = entry.option().orElse(null);
				if (option != null) addOption(x, y, width, entry, option);
			}
			column++;
			if (column == columns) {
				column = 0;
				y += 28;
			}
		}

		int footer = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.translatable("mtlrender.shaderpacks.reset"), button -> {
			model.resetAll();
			refresh();
		}).bounds(this.width / 2 - 154, footer, 98, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("mtlrender.shaderpacks.back"), button -> back())
			.bounds(this.width / 2 - 50, footer, 98, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("mtlrender.shaderpacks.done"), button -> done())
			.bounds(this.width / 2 + 54, footer, 98, 20).build());
	}

	private void addProfile(int x, int y, int width, String label) {
		List<String> profiles = model.profiles();
		if (profiles.isEmpty()) return;
		String current = model.value("profile");
		if (!profiles.contains(current)) current = profiles.getFirst();
		CycleButton<String> selector = CycleButton.builder(value -> Component.literal(label + ": " + model.profileLabel(value)), current)
			.withValues(profiles)
			.create(x, y, width, 20, Component.literal(label), (button, value) -> {
				model.selectProfile(value);
				button.setMessage(Component.literal(label + ": " + model.profileLabel(value)));
			});
		this.addRenderableWidget(selector);
	}

	private void addOption(int x, int y, int width, PackOptionsModel.ScreenEntry entry, PackOption option) {
		if (model.isSlider(option.name()) && model.sliderValues(option.name()).size() > 1) {
			this.addRenderableWidget(new OptionSlider(x, y, width, entry.label(), option.name(), model.sliderValues(option.name())));
			return;
		}
		List<String> values = option.isBoolean() ? List.of("false", "true") : option.allowedValues();
		if (values.isEmpty()) return;
		String current = model.value(option.name());
		if (!values.contains(current)) current = values.getFirst();
		CycleButton<String> button = CycleButton.builder(value -> Component.literal(entry.label() + ": " + value), current)
			.withValues(values)
			.create(x, y, width, 20, Component.literal(entry.label()), (cycle, value) -> model.set(option.name(), value));
		this.addRenderableWidget(button);
	}

	private void refresh() {
		this.minecraft.gui.setScreen(new ShaderPackOptionsScreen(lastScreen, exitScreen, manager, model, screenName));
	}

	private void back() {
		if (screenName.isEmpty()) model.cancel();
		this.minecraft.gui.setScreen(lastScreen);
	}

	private void done() {
		PackManager.Result result = model.save(manager, manager.activeName());
		if (result.success()) this.minecraft.gui.setScreen(exitScreen);
	}

	@Override
	public void onClose() {
		back();
	}

	private final class OptionSlider extends AbstractSliderButton {
		private final String name;
		private final String label;
		private final List<String> values;

		private OptionSlider(int x, int y, int width, String label, String name, List<String> values) {
			super(x, y, width, 20, Component.literal(label), position(ShaderPackOptionsScreen.this.model, name, values));
			this.label = label;
			this.name = name;
			this.values = values;
			updateMessage();
		}

		private static double position(PackOptionsModel model, String name, List<String> values) {
			String current = model.value(name);
			int index = values.indexOf(current);
			return index < 0 ? 0.0 : (double) index / (values.size() - 1);
		}

		@Override
		protected void updateMessage() {
			int index = Math.max(0, Math.min(values.size() - 1, (int) Math.round(value * (values.size() - 1))));
			setMessage(Component.literal(label + ": " + values.get(index)));
		}

		@Override
		protected void applyValue() {
			int index = Math.max(0, Math.min(values.size() - 1, (int) Math.round(value * (values.size() - 1))));
			model.set(name, values.get(index));
		}
	}
}
