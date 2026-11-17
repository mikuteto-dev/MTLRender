package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GbufferInputAdapterTest {
	@Test
	void mapsOnlyInputsProvidedByTheActiveFormat() {
		String source = "in vec3 mtlr_Position;\n"
			+ "in vec4 mtlr_Color;\n"
			+ "in vec2 mtlr_UV0;\n"
			+ "in ivec2 mtlr_UV2;\n"
			+ "in vec4 mtlr_Normal;\n";
		String adapted = GbufferInputAdapter.adapt(source, GbufferPipelineSpec.InputProfile.Entity);
		assertTrue(adapted.contains("in vec3 Position;"));
		assertTrue(adapted.contains("in vec4 Color;"));
		assertTrue(adapted.contains("in vec2 UV0;"));
		assertTrue(adapted.contains("in ivec2 UV2;"));
		assertTrue(adapted.contains("in vec4 Normal;"));

		String positionOnly = GbufferInputAdapter.adapt(source, GbufferPipelineSpec.InputProfile.Position);
		assertTrue(positionOnly.contains("in vec3 Position;"));
		assertTrue(positionOnly.contains("mtlr_Color"));
		assertTrue(positionOnly.contains("mtlr_UV0"));
		assertTrue(positionOnly.contains("mtlr_UV2"));
		assertTrue(positionOnly.contains("mtlr_Normal"));
	}

	@Test
	void rejectsAliasCollision() {
		assertThrows(IllegalArgumentException.class, () -> GbufferInputAdapter.adapt(
			"in vec3 Position;\nin vec3 mtlr_Position;\n", GbufferPipelineSpec.InputProfile.Position));
	}

	@Test
	void defaultsAbsentOptionalInputsWithoutAddingVertexAttributes() {
		String source = "#version 150\nin vec3 mtlr_Position;\nin vec4 at_tangent;\nvoid main() { vec4 x = mc_Entity + at_tangent; }\n";
		String adapted = GbufferInputAdapter.adapt(source, GbufferPipelineSpec.InputProfile.Position);
		assertTrue(adapted.startsWith("#version 150\n"));
		assertTrue(adapted.contains("in vec3 Position;"));
		assertFalse(adapted.contains("in vec4 at_tangent;"));
		assertTrue(adapted.contains("const vec4 at_tangent = vec4(0.0);"));
		assertTrue(adapted.contains("const vec4 mc_Entity = vec4(0.0);"));
	}

	@Test
	void defaultsConditionalDeclarations() {
		String source = "#if FIRST\nattribute vec4 mc_Entity;\n#else\nattribute vec4 mc_Entity;\n#endif\n";
		String adapted = GbufferInputAdapter.adapt(source, GbufferPipelineSpec.InputProfile.Position);
		assertFalse(adapted.contains("attribute vec4 mc_Entity"));
		assertEquals(2, adapted.split("const vec4 mc_Entity", -1).length - 1);
	}

	@Test
	void selectedSourceIsAdaptedButVanillaFallbackIsByteForByte() {
		Identifier vanillaVertex = RenderPipelines.SKY.getVertexShader();
		Identifier vanillaFragment = RenderPipelines.SKY.getFragmentShader();
		ShaderSource vanilla = (id, type) -> "vanilla-source";
		GbufferShaderSource fallback = new GbufferShaderSource(vanilla, vanillaVertex, vanillaFragment,
			Identifier.fromNamespaceAndPath("mtlrender", "fallback-v"),
			Identifier.fromNamespaceAndPath("mtlrender", "fallback-f"),
			"vanilla-source", "vanilla-fragment");
		assertEquals("vanilla-source", fallback.get(fallback.vertex(), ShaderType.VERTEX));

		GbufferShaderSource selected = new GbufferShaderSource(vanilla, vanillaVertex, vanillaFragment,
			Identifier.fromNamespaceAndPath("mtlrender", "selected-v"),
			Identifier.fromNamespaceAndPath("mtlrender", "selected-f"),
			"in vec3 mtlr_Position;", "pack-fragment", GbufferPipelineSpec.InputProfile.Position);
		assertEquals("in vec3 Position;", selected.get(selected.vertex(), ShaderType.VERTEX));
		assertEquals("pack-fragment", selected.get(selected.fragment(), ShaderType.FRAGMENT));
	}
}
