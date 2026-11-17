package dev.mikuteto.mtlrender.mixin;

import dev.mikuteto.mtlrender.config.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin {
	@Inject(method = "addOptions", at = @At("TAIL"))
	private void mtlrender$addSettingsButton(CallbackInfo ci) {
		((OptionsSubScreenAccessor) this).getList().addHeader(Component.translatable("mtlrender.options.header"));
		((OptionsSubScreenAccessor) this).getList().addBig(Button.builder(
			Component.translatable("mtlrender.options.open"),
			button -> Minecraft.getInstance().gui.setScreen(new SettingsScreen((Screen) (Object) this, Minecraft.getInstance().options))
		).build());
	}
}
