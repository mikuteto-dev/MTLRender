package dev.mikuteto.mtlrender.pack.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class ComputePlanTest {
	@Test
	void acceptsBaseNumberedAndLetterPassNames() {
		assertEquals("setup", ComputePlan.PassId.base(ComputePlan.PassKind.SETUP).sourceName());
		assertEquals("composite12", ComputePlan.PassId.numbered(ComputePlan.PassKind.COMPOSITE, 12).sourceName());
		ComputePlan.PassId variant = ComputePlan.PassId.variant(ComputePlan.PassKind.SHADOWCOMP, 3, 'z');
		assertEquals(variant, ComputePlan.PassId.parse("shadowcomp3_z"));
		assertEquals("begin_a", ComputePlan.PassId.parse("begin_a").sourceName());
	}

	@Test
	void acceptsAllDispatchKinds() {
		assertEquals(new ComputePlan.Dimensions(4, 5, 6),
				((ComputePlan.Absolute) new ComputePlan.Absolute(new ComputePlan.Dimensions(4, 5, 6))).groups());
		assertEquals(2, new ComputePlan.RenderRelative(2, 3).width());
		assertEquals(3, new ComputePlan.RenderRelative(2, 3).height());
		assertEquals(7, new ComputePlan.ShadowRelative(7, 8).width());
		assertEquals(8, new ComputePlan.ShadowRelative(7, 8).height());
		assertEquals(new ComputePlan.Default(), new ComputePlan.Default());
		assertEquals(16L, new ComputePlan.Indirect("dispatch", 16).offsetBytes());
	}

	@Test
	void acceptsAllAccessModesAndImageDimensions() {
		ComputePlan.SsboSpec read = new ComputePlan.SsboSpec("read", 0, 64, ComputePlan.Access.READ);
		ComputePlan.SsboSpec write = new ComputePlan.SsboSpec("write", 1, 64, ComputePlan.Access.WRITE);
		ComputePlan.SsboSpec both = new ComputePlan.SsboSpec("both", 2, 64, ComputePlan.Access.READ_WRITE);
		List<ComputePlan.CustomImageSpec> images = List.of(
			new ComputePlan.CustomImageSpec("one", 0, ComputePlan.ImageDimension.ONE,
					ComputePlan.Dimensions.one(32), ComputePlan.Access.READ),
			new ComputePlan.CustomImageSpec("two", 1, ComputePlan.ImageDimension.TWO,
					ComputePlan.Dimensions.two(32, 16), ComputePlan.Access.WRITE),
			new ComputePlan.CustomImageSpec("three", 2, ComputePlan.ImageDimension.THREE,
					new ComputePlan.Dimensions(8, 8, 8), ComputePlan.Access.READ_WRITE));
		ComputePlan.ResolvedPassPlan plan = new ComputePlan.ResolvedPassPlan(
			ComputePlan.PassId.base(ComputePlan.PassKind.PREPARE), new ComputePlan.Default(),
				List.of(read, write, both), images);
		assertEquals(3, plan.ssbos().size());
		assertEquals(3, plan.images().size());
	}

	@Test
	void rejectsInvalidDimensionsNamesAndPasses() {
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.Dimensions(0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.RenderRelative(-1, 1));
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.ShadowRelative(1, 0));
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.SsboSpec("", 0, 1, ComputePlan.Access.READ));
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.Indirect("dispatch", -1));
		assertThrows(IllegalArgumentException.class, () -> ComputePlan.PassId.parse("missing"));
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.CustomImageSpec("bad", 0,
				ComputePlan.ImageDimension.ONE, new ComputePlan.Dimensions(1, 2, 1), ComputePlan.Access.READ));
	}

	@Test
	void rejectsConflictingResourceDeclarations() {
		ComputePlan.SsboSpec first = new ComputePlan.SsboSpec("buffer", 0, 4, ComputePlan.Access.READ);
		ComputePlan.SsboSpec sameName = new ComputePlan.SsboSpec("buffer", 1, 4, ComputePlan.Access.WRITE);
		ComputePlan.CustomImageSpec firstImage = new ComputePlan.CustomImageSpec("image", 0,
				ComputePlan.ImageDimension.TWO, ComputePlan.Dimensions.two(2, 2), ComputePlan.Access.READ);
		ComputePlan.CustomImageSpec sameSlot = new ComputePlan.CustomImageSpec("other", 0,
				ComputePlan.ImageDimension.TWO, ComputePlan.Dimensions.two(2, 2), ComputePlan.Access.WRITE);
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.ResolvedPassPlan(
				ComputePlan.PassId.base(ComputePlan.PassKind.SETUP), new ComputePlan.Default(), List.of(first, sameName), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new ComputePlan.ResolvedPassPlan(
				ComputePlan.PassId.base(ComputePlan.PassKind.SETUP), new ComputePlan.Default(), List.of(), List.of(firstImage, sameSlot)));
	}
}
