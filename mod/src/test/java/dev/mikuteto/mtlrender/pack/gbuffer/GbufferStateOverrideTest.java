package dev.mikuteto.mtlrender.pack.gbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.GpuFormat;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import org.junit.jupiter.api.Test;

final class GbufferStateOverrideTest {
	@Test
	void appliesProgramAndSparseBufferOverrides() {
		ShaderProperties properties = new ShaderProperties("", ""
			+ "blend.gbuffers_terrain = SRC_ALPHA ONE ONE ZERO\n"
			+ "blend.gbuffers_terrain.colortex5 = off\n"
			+ "alphaTest.gbuffers_terrain = GREATER 0.1\n");
		GbufferStateOverride override = GbufferStateOverride.forProgram(properties, "gbuffers_terrain", List.of(0, 5, 11))
			.orElseThrow();
		ColorTargetState vanilla = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);
		assertTrue(override.alphaTest().isPresent());
		assertTrue(override.colorTarget(0, vanilla, List.of(0, 5, 11)).blendFunction().isPresent());
		assertTrue(override.colorTarget(1, vanilla, List.of(0, 5, 11)).blendFunction().isEmpty());
		assertTrue(override.colorTarget(2, vanilla, List.of(0, 5, 11)).blendFunction().isPresent());
	}

	@Test
	void invalidOptionalValuesDoNotCreateState() {
		ShaderProperties properties = new ShaderProperties("", "blend.gbuffers_terrain = invalid\n");
		assertTrue(GbufferStateOverride.forProgram(properties, "gbuffers_terrain", List.of(0)).isEmpty());
		assertEquals(0, GbufferStateOverride.forProgram(properties, "gbuffers_other", List.of(0)).stream().count());
	}
}
