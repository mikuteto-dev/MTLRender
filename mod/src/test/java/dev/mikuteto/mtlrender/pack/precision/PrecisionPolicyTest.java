package dev.mikuteto.mtlrender.pack.precision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PrecisionPolicyTest {
	private static BufferUsage usage(String id, String format, String metal, int reads, int writes) {
		return new BufferUsage(id, format, metal, ChannelMask.known(reads), ChannelMask.known(writes),
			new NumericUsage(NumericUsage.Signedness.FLOAT, NumericUsage.PrecisionEvidence.PROVEN_SAFE),
			BufferUsage.ResourceType.COLOR_ATTACHMENT, false);
	}

	@Test
	void defaultsLeaveFormatsUnchanged() {
		PrecisionDecision decision = PrecisionPolicy.defaults().decide(
			usage("colortex0", "RGBA32F", "RGBA32_FLOAT", ChannelMask.RGBA, ChannelMask.RGBA));
		assertFalse(decision.changed());
		assertEquals("RGBA32_FLOAT", decision.effectiveMetalFormat());
	}

	@Test
	void reductionsAreIndependent() {
		BufferUsage full = usage("a", "RGBA32F", "RGBA32_FLOAT", ChannelMask.RGBA, ChannelMask.RGBA);
		BufferUsage rgb = usage("b", "RGBA16F", "RGBA16_FLOAT", ChannelMask.RGB, ChannelMask.RGB);
		assertEquals(ReductionKind.RGBA32F_TO_RGBA16F, new PrecisionPolicy(true, false).decide(full).reduction());
		assertEquals(ReductionKind.NONE, new PrecisionPolicy(true, false).decide(rgb).reduction());
		assertEquals(ReductionKind.RGBA16F_TO_RG11B10F, new PrecisionPolicy(false, true).decide(rgb).reduction());
		assertEquals(ReductionKind.NONE, new PrecisionPolicy(false, true).decide(full).reduction());
	}

	@Test
	void alphaReadRejectsRgbReduction() {
		BufferUsage alpha = usage("a", "RGBA16F", "RGBA16_FLOAT", ChannelMask.RGBA, ChannelMask.RGB);
		PrecisionDecision decision = new PrecisionPolicy(false, true).decide(alpha);
		assertFalse(decision.changed());
		assertEquals("alpha is read or written", decision.reason());
	}

	@Test
	void signedAndUnknownEvidenceRejectsReduction() {
		BufferUsage signed = new BufferUsage("signed", "RGBA32F", "RGBA32_FLOAT", ChannelMask.known(ChannelMask.RGB),
			ChannelMask.known(ChannelMask.RGB), new NumericUsage(NumericUsage.Signedness.SIGNED,
				NumericUsage.PrecisionEvidence.PROVEN_SAFE), BufferUsage.ResourceType.COLOR_ATTACHMENT, false);
		BufferUsage unknown = new BufferUsage("unknown", "RGBA32F", "RGBA32_FLOAT", ChannelMask.unknownMask(),
			ChannelMask.known(ChannelMask.RGB), NumericUsage.unknown(), BufferUsage.ResourceType.COLOR_ATTACHMENT, false);
		PrecisionPolicy policy = new PrecisionPolicy(true, false);
		assertFalse(policy.decide(signed).changed());
		assertFalse(policy.decide(unknown).changed());
	}

	@Test
	void reportOrderingIsStableByBufferId() {
		PrecisionReport report = new PrecisionPolicy(true, true).report(List.of(
			usage("z", "RGBA32F", "RGBA32_FLOAT", ChannelMask.RGBA, ChannelMask.RGBA),
			usage("a", "RGBA16F", "RGBA16_FLOAT", ChannelMask.RGB, ChannelMask.RGB)));
		assertEquals(List.of("a", "z"), report.decisions().stream().map(PrecisionDecision::bufferId).toList());
		assertTrue(report.decisions().get(0).changed());
	}
}
