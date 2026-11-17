package dev.mikuteto.mtlrender.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProfileSetTest {
	@Test
	void preservesProfilesAndTerminatesInheritedCycles() {
		Map<String, List<String>> source = new LinkedHashMap<>();
		source.put("A", List.of("profile.B", "VALUE=1"));
		source.put("B", List.of("profile.A", "FLAG"));
		Map<String, PackOption> options = Map.of(
			"FLAG", new PackOption(PackOption.Type.DEFINE, "FLAG", true, null, false, List.of(), null),
			"VALUE", new PackOption(PackOption.Type.DEFINE, "VALUE", false, "0", false, List.of("0", "1"), null));

		ProfileSet profiles = ProfileSet.from(source, options);

		assertEquals(List.of("A", "B"), List.copyOf(profiles.profiles().keySet()));
		assertEquals("1", profiles.select("A").orElseThrow().options().get("VALUE"));
		assertEquals("true", profiles.select("A").orElseThrow().options().get("FLAG"));
		assertTrue(profiles.select("B").isPresent());
		assertEquals("A", profiles.match(Map.of("VALUE", "1", "FLAG", "true"), options)
			.orElseThrow().name());
	}
}
