package dev.mikuteto.mtlrender.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackOptionFileTest {
	@TempDir
	Path directory;

	@Test
	void roundTripsOptionValuesInStableOrder() throws IOException {
		Path file = directory.resolve("pack.txt");
		Map<String, String> values = new LinkedHashMap<>();
		values.put("PROFILE", "QUALITY");
		values.put("ENABLE_SHADOWS", "true");
		values.put("shadowMapResolution", "2048");
		values.put("EDGE_HIGHLIGHT_SCALE", "16.0");
		values.put("unknown.option", "kept");
		values.put("empty", "");

		PackOptionFile.write(file, values);

		assertEquals(Map.of(
			"PROFILE", "QUALITY",
			"ENABLE_SHADOWS", "true",
			"shadowMapResolution", "2048",
			"EDGE_HIGHLIGHT_SCALE", "16.0",
			"unknown.option", "kept",
			"empty", ""), PackOptionFile.read(file));
		assertEquals("EDGE_HIGHLIGHT_SCALE=16.0\nENABLE_SHADOWS=true\nPROFILE=QUALITY\nempty=\nshadowMapResolution=2048\nunknown.option=kept\n",
			Files.readString(file, StandardCharsets.ISO_8859_1));
	}

	@Test
	void followsIrisPropertiesLineRules() throws IOException {
		Path file = directory.resolve("iris.txt");
		Files.writeString(file,
			"# comment\r\n"
				+ "! comment\r\n"
				+ " boolean = true \r\n"
				+ "string: value\r\n"
				+ "numeric 2048\r\n"
				+ "profile=QUALITY\r\n"
				+ "unknown.option=kept\r\n"
				+ "empty=\r\n"
				+ "noSeparator\r\n"
				+ "broken=\\uZZZZ\r\n",
			StandardCharsets.ISO_8859_1);

		Map<String, String> values = assertDoesNotThrow(() -> PackOptionFile.read(file));

		assertEquals("true ", values.get("boolean"));
		assertEquals("value", values.get("string"));
		assertEquals("2048", values.get("numeric"));
		assertEquals("QUALITY", values.get("profile"));
		assertEquals("kept", values.get("unknown.option"));
		assertEquals("", values.get("empty"));
		assertEquals("", values.get("noSeparator"));
		assertEquals("\\uZZZZ", values.get("broken"));
	}
}
