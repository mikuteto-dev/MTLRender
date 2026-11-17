package dev.mikuteto.mtlrender.config;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

import dev.mikuteto.mtlrender.pack.PackListModel;
import dev.mikuteto.mtlrender.pack.PackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class ShaderPackListScreen extends Screen {
	private static final Component TITLE = Component.translatable("mtlrender.shaderpacks.title");
	private final Screen parent;
	private final Consumer<String> openOptions;
	private final PackListModel model;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private PackList list;
	private Button optionsButton;
	private Button applyButton;
	private Component errorMessage;

	public ShaderPackListScreen(Screen parent, Consumer<String> openOptions) {
		super(TITLE);
		this.parent = parent;
		this.openOptions = openOptions;
		this.model = new PackListModel(PackManager.get());
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(TITLE, this.font);
		this.list = this.layout.addToContents(new PackList(this.minecraft, this.model.entries()));
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.translatable("mtlrender.shaderpacks.openFolder"), button -> openFolder()).build());
		this.optionsButton = footer.addChild(Button.builder(Component.translatable("mtlrender.shaderpacks.options"), button -> openOptions()).build());
		this.applyButton = footer.addChild(Button.builder(Component.translatable("mtlrender.shaderpacks.apply"), button -> apply(false)).build());
		footer.addChild(Button.builder(Component.translatable("mtlrender.shaderpacks.back"), button -> closeWithoutApplying()).build());
		footer.addChild(Button.builder(Component.translatable("mtlrender.shaderpacks.done"), button -> apply(true)).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
		selectInitialEntry();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		if (this.list != null) {
			this.list.updateSize(this.width, this.layout);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (this.errorMessage != null) {
			graphics.text(this.font, this.errorMessage, this.width / 2 - 150, this.layout.getHeaderHeight() - 12, 0xFFFF5555);
		}
	}

	private void selectInitialEntry() {
		String selection = this.model.stagedSelection();
		PackEntry entry = this.list.entryFor(selection);
		if (entry == null) {
			entry = this.list.entryFor("");
		}
		if (entry != null) {
			this.list.setSelected(entry);
		}
		updateButtons();
	}

	private void select(PackEntry entry) {
		if (this.model.select(entry.value().id())) {
			this.list.setSelected(entry);
			this.errorMessage = null;
			updateButtons();
		} else {
			showError();
		}
	}

	private void updateButtons() {
		boolean off = this.model.stagedSelection().isEmpty();
		this.optionsButton.active = !off;
		this.applyButton.active = !this.model.stagedSelection().equals(this.model.activeSelection());
	}

	private void openOptions() {
		PackManager.Result result = this.model.applySelection();
		if (result.success()) {
			this.errorMessage = null;
			this.openOptions.accept(this.model.stagedSelection());
		} else {
			showError();
		}
	}

	private void apply(boolean close) {
		PackManager.Result result = this.model.applySelection();
		if (result.success()) {
			this.errorMessage = null;
			updateButtons();
			if (close) {
				this.minecraft.gui.setScreen(this.parent);
			}
		} else {
			showError();
		}
	}

	private void showError() {
		this.model.loadError().ifPresent(error -> {
			String message = error.message();
			String shortMessage = message.length() > 160 ? message.substring(0, 160) : message;
			this.errorMessage = Component.translatable("mtlrender.shaderpacks.error", shortMessage);
		});
	}

	private void openFolder() {
		try {
			Files.createDirectories(this.model.openFolder());
			Util.getPlatform().openPath(this.model.openFolder());
		} catch (IOException exception) {
			this.errorMessage = Component.translatable("mtlrender.shaderpacks.error", exception.getMessage());
		}
	}

	private void closeWithoutApplying() {
		this.minecraft.gui.setScreen(this.parent);
	}

	private final class PackList extends ObjectSelectionList<PackEntry> {
		private PackList(Minecraft minecraft, List<PackListModel.Entry> entries) {
			super(minecraft, ShaderPackListScreen.this.width, ShaderPackListScreen.this.layout.getContentHeight(), ShaderPackListScreen.this.layout.getHeaderHeight(), 24);
			for (PackListModel.Entry entry : entries) {
				this.addEntry(new PackEntry(entry));
			}
		}

		private PackEntry entryFor(String id) {
			for (PackEntry entry : this.children()) {
				if (entry.value().id().equals(id)) {
					return entry;
				}
			}
			return null;
		}

		@Override
		public int getRowWidth() {
			return Math.min(400, this.width - 20);
		}
	}

	private final class PackEntry extends ObjectSelectionList.Entry<PackEntry> {
		private final PackListModel.Entry value;

		private PackEntry(PackListModel.Entry value) {
			this.value = value;
		}

		private PackListModel.Entry value() {
			return this.value;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			int color = this.value.id().equals(ShaderPackListScreen.this.model.stagedSelection()) ? 0xFFFFFF55 : 0xFFFFFFFF;
			Component name = this.value.off()
				? Component.translatable("mtlrender.shaderpacks.off")
				: Component.literal(this.value.displayName());
			graphics.text(ShaderPackListScreen.this.font, name, this.getContentX(), this.getContentY() + 5, color);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (event.button() == 0) {
				ShaderPackListScreen.this.select(this);
				return true;
			}
			return false;
		}

		@Override
		public Component getNarration() {
			Component name = this.value.off()
				? Component.translatable("mtlrender.shaderpacks.off")
				: Component.literal(this.value.displayName());
			return Component.translatable("mtlrender.shaderpacks.entry", name);
		}
	}
}
