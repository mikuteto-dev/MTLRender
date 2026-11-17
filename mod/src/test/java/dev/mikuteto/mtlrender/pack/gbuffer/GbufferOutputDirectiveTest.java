package dev.mikuteto.mtlrender.pack.gbuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GbufferOutputDirectiveTest {
	@Test
	void parsesIrisAndOptifineSpellings() {
		GbufferOutputDirective draw = GbufferOutputDirective.parse("void main() {} /* DRAWBUFFERS:0123 */");
		assertEquals(ListValue.of(0, 1, 2, 3), ListValue.from(draw));

		GbufferOutputDirective render = GbufferOutputDirective.parse("/* RENDERTARGETS: 0,1 */");
		assertEquals(ListValue.of(0, 1), ListValue.from(render));
	}

	@Test
	void keepsExplicitUnusedSlots() {
		GbufferOutputDirective directive = GbufferOutputDirective.parse("/* DRAWBUFFERS:0-2 */");
		assertEquals(3, directive.outputs().size());
		assertEquals(false, directive.outputs().get(1).present());
	}

	@Test
	void rejectsDuplicateInvalidAndTooManyTargets() {
		assertThrows(IllegalArgumentException.class, () -> GbufferOutputDirective.parse("/* DRAWBUFFERS:00 */"));
		assertThrows(IllegalArgumentException.class, () -> GbufferOutputDirective.parse("/* RENDERTARGETS: 0,16 */"));
		assertThrows(IllegalArgumentException.class, () -> GbufferOutputDirective.parse("/* RENDERTARGETS: 0,1,2,3,4,5,6,7,8 */"));
	}

	@Test
	void absentDirectiveIsDistinguished() {
		assertEquals(false, GbufferOutputDirective.find("void main() {}").isPresent());
	}

	@Test
	void resolvesLegacyNames() {
		assertEquals("colortex0", GbufferOutputLayout.canonicalTarget("gcolor").orElseThrow());
		assertEquals("colortex3", GbufferOutputLayout.canonicalTarget("composite").orElseThrow());
		assertEquals("colortex7", GbufferOutputLayout.canonicalTarget("gaux4").orElseThrow());
	}

	private record ListValue(java.util.List<Integer> values) {
		static ListValue of(Integer... values) {
			return new ListValue(java.util.List.of(values));
		}

		static ListValue from(GbufferOutputDirective directive) {
			return new ListValue(directive.outputs().stream().filter(GbufferOutputDirective.OptionalInt::present)
				.map(GbufferOutputDirective.OptionalInt::value).toList());
		}
	}
}
