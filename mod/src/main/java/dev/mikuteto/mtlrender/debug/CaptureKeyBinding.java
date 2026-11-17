package dev.mikuteto.mtlrender.debug;

import java.nio.file.Path;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

public final class CaptureKeyBinding {
	public static final KeyMapping KEY = new KeyMapping(
		"key.mtlrender.capture", InputConstants.UNKNOWN.getValue(), KeyMapping.Category.DEBUG
	);

	private static final boolean ENABLED = "1".equals(System.getenv("MTL_CAPTURE_ENABLED"));

	private CaptureKeyBinding() {
	}

	public static void tick() {
		if (!KEY.consumeClick() || !ENABLED || !MetalAccess.active()) {
			return;
		}
		Path path = FabricLoader.getInstance().getGameDir().resolve("mtlrender-" + System.currentTimeMillis() + ".gputrace");
		MetalAccess.captureNext(path);
	}
}
