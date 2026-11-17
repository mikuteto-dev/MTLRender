package dev.mikuteto.mtlrender.pack.gbuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.CompareOp;
import org.junit.jupiter.api.Test;

final class GbufferShaderSourceTest {
	@Test
	void injectsThresholdBeforeFragmentCompletes() {
		String source = "#version 460\nvec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n";
		String patched = GbufferShaderSource.inject(source,
			new GbufferStateOverride.AlphaTest(CompareOp.GREATER_THAN, 0.1f));
		assertTrue(patched.contains("mtlr_gbuffer_main"));
		assertTrue(patched.contains("fragColor.a <= 0.1"));
		assertTrue(patched.indexOf("mtlr_gbuffer_main();") < patched.indexOf("discard;"));
	}
}
