package dev.mikuteto.mtlrender.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

class ConfigCompatibilityTest {
	private final Gson gson = new GsonBuilder().create();

	@Test
	void oldFilesGetRenderDefaultsAndAllFieldsRoundTrip() {
		Config.Misc06 defaults = gson.fromJson("{}", Config.Misc06.class);
		assertTrue(defaults.lightmap);
		assertTrue(defaults.zeroFov);
		assertTrue(defaults.emptyState);
		assertFalse(defaults.soundHandles);

		defaults.lightmap = false;
		defaults.zeroFov = false;
		defaults.emptyState = false;
		defaults.soundHandles = true;
		Config.Misc06 restored = gson.fromJson(gson.toJson(defaults), Config.Misc06.class);
		assertFalse(restored.lightmap);
		assertFalse(restored.zeroFov);
		assertFalse(restored.emptyState);
		assertTrue(restored.soundHandles);
	}
}
