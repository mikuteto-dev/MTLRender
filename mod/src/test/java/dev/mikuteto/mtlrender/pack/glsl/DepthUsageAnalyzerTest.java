package dev.mikuteto.mtlrender.pack.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DepthUsageAnalyzerTest {
	@Test
	void detectsWorldAndShadowAliasesAndSamplerKinds() {
		DepthUsage usage = DepthUsageAnalyzer.analyze("""
			uniform sampler2D depthtex0;
			uniform sampler2DShadow shadowtex0;
			#define sceneDepth depthtex0
			float a = texture(sceneDepth, vec2(0.0)).r;
			float b = texture(shadowtex0, vec3(uv, 0.5));
			""");

		assertEquals(3, usage.resources().size());
		assertEquals(DepthResource.SamplerKind.SAMPLE, usage.resources().stream()
			.filter(resource -> resource.name().equals("sceneDepth")).findFirst().orElseThrow().samplerKind());
		assertEquals(DepthResource.SamplerKind.COMPARE_SAMPLE, usage.resources().stream()
			.filter(resource -> resource.name().equals("shadowtex0")).findFirst().orElseThrow().samplerKind());
		assertTrue(usage.hasWorldDepth());
		assertTrue(usage.hasShadowDepth());
	}

	@Test
	void detectsEveryDepthFamilyAliasWithoutChangingColorSamplers() {
		DepthUsage usage = DepthUsageAnalyzer.analyze("""
			uniform sampler2D color;
			uniform sampler2D gdepthtex;
			uniform sampler2D shadowtex1HW;
			uniform sampler2D shadowtex0DH;
			uniform sampler2D watershadow;
			float x = texture(color, uv).r + texture(gdepthtex, uv).r;
			float y = texelFetch(shadowtex1HW, ivec2(uv), 0).r;
			float z = textureLod(shadowtex0DH, uv, 0.0).r + texture(watershadow, uv).r;
			""");

		assertEquals(4, usage.resources().size());
		assertFalse(usage.resources().stream().anyMatch(resource -> resource.name().equals("color")));
		assertTrue(usage.resources().stream().anyMatch(resource -> resource.name().equals("gdepthtex")));
		assertTrue(usage.resources().stream().anyMatch(resource -> resource.name().equals("shadowtex1HW")));
		assertTrue(usage.resources().stream().anyMatch(resource -> resource.name().equals("shadowtex0DH")));
		assertTrue(usage.resources().stream().anyMatch(resource -> resource.name().equals("watershadow")));
	}

	@Test
	void marksFragmentDepthProducerAndIgnoresComments() {
		DepthUsage producer = DepthUsageAnalyzer.analyze("gl_FragDepth = value;");
		DepthUsage comment = DepthUsageAnalyzer.analyze("// gl_FragDepth\nfloat x = 1.0;");

		assertTrue(producer.writesFragmentDepth());
		assertFalse(comment.writesFragmentDepth());
	}
}
