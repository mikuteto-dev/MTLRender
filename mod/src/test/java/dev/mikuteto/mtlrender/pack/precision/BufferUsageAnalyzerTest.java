package dev.mikuteto.mtlrender.pack.precision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikuteto.mtlrender.pack.Program;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BufferUsageAnalyzerTest {
	@Test
	void mapsDirectiveOutputsAndSwizzles() {
		Program program = program("/* DRAWBUFFERS:03 */\nlayout(location = 0) out vec4 first;\nfirst.rgb = vec3(1.0);\ngl_FragData[1].a = 1.0;");
		List<BufferUsage> usage = BufferUsageAnalyzer.analyze(program, new TranslatedOutputReflection(Map.of("first", 0)));
		BufferUsage zero = usage.stream().filter(item -> item.id().equals("colortex0")).findFirst().orElseThrow();
		BufferUsage three = usage.stream().filter(item -> item.id().equals("colortex3")).findFirst().orElseThrow();
		assertEquals(ChannelMask.RGB, zero.writes().bits());
		assertEquals(ChannelMask.ALPHA, three.writes().bits());
	}

	@Test
	void separatesTextureReadsAndLegacyAliases() {
		Program program = program("/* RENDERTARGETS: 0, 4 */\nvoid main() { gl_FragColor = texture(gcolor, uv).rgb; gl_FragData[1].rgb = texture(colortex4, uv).a.xxx; }");
		List<BufferUsage> usage = BufferUsageAnalyzer.analyze(program, TranslatedOutputReflection.empty());
		BufferUsage zero = usage.stream().filter(item -> item.id().equals("colortex0")).findFirst().orElseThrow();
		BufferUsage four = usage.stream().filter(item -> item.id().equals("colortex4")).findFirst().orElseThrow();
		assertEquals(ChannelMask.RGB, zero.reads().bits());
		assertTrue(four.reads().includes(ChannelMask.ALPHA));
	}

	@Test
	void dynamicAccessRemainsUnknown() {
		Program program = program("/* DRAWBUFFERS:0 */\nvoid main() { gl_FragData[index] = value; }");
		BufferUsage usage = BufferUsageAnalyzer.analyze(program, TranslatedOutputReflection.empty()).get(0);
		assertTrue(usage.dynamicOutputSelection());
		assertTrue(usage.writes().unknown());
	}

	private static Program program(String fragment) {
		return new Program("test", Map.of(Program.Stage.FRAGMENT, fragment));
	}
}
