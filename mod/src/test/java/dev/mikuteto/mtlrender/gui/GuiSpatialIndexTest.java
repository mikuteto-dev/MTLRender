package dev.mikuteto.mtlrender.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;

class GuiSpatialIndexTest {
	@Test
	void emptyAndNullBoundsDoNotIntersect() {
		GuiSpatialIndex index = new GuiSpatialIndex();

		index.insert(null);

		assertFalse(index.hasIntersection(null));
		assertFalse(index.hasIntersection(new ScreenRectangle(0, 0, 1, 1)));
	}

	@Test
	void boundaryContactDoesNotIntersect() {
		GuiSpatialIndex index = new GuiSpatialIndex();
		index.insert(new ScreenRectangle(0, 0, 10, 10));

		assertFalse(index.hasIntersection(new ScreenRectangle(10, 0, 5, 5)));
		assertFalse(index.hasIntersection(new ScreenRectangle(0, 10, 5, 5)));
		assertTrue(index.hasIntersection(new ScreenRectangle(9, 9, 2, 2)));
	}

	@Test
	void duplicatesAndLargeRectanglesAreIndexed() {
		GuiSpatialIndex index = new GuiSpatialIndex();
		ScreenRectangle duplicate = new ScreenRectangle(32, 32, 8, 8);
		ScreenRectangle large = new ScreenRectangle(-100_000, -100_000, 200_000, 200_000);
		index.insert(duplicate);
		index.insert(duplicate);
		index.insert(large);

		assertTrue(index.hasIntersection(new ScreenRectangle(33, 33, 1, 1)));
		assertTrue(index.hasIntersection(new ScreenRectangle(99_999, 0, 2, 2)));
		assertFalse(index.hasIntersection(new ScreenRectangle(100_000, 0, 2, 2)));
	}

	@Test
	void resetRemovesAllRectangles() {
		GuiSpatialIndex index = new GuiSpatialIndex();
		index.insert(new ScreenRectangle(0, 0, 100, 100));

		index.reset();

		assertFalse(index.hasIntersection(new ScreenRectangle(1, 1, 1, 1)));
	}

	@Test
	void independentIndexesRepresentDifferentNodes() {
		GuiSpatialIndex parent = new GuiSpatialIndex();
		GuiSpatialIndex child = new GuiSpatialIndex();
		parent.insert(new ScreenRectangle(0, 0, 10, 10));
		child.insert(new ScreenRectangle(100, 100, 10, 10));

		assertTrue(parent.hasIntersection(new ScreenRectangle(1, 1, 1, 1)));
		assertFalse(parent.hasIntersection(new ScreenRectangle(101, 101, 1, 1)));
		assertTrue(child.hasIntersection(new ScreenRectangle(101, 101, 1, 1)));
		assertFalse(child.hasIntersection(new ScreenRectangle(1, 1, 1, 1)));
	}

	@Test
	void randomQueriesMatchBruteForce() {
		GuiSpatialIndex index = new GuiSpatialIndex();
		List<ScreenRectangle> rectangles = new ArrayList<>();
		Random random = new Random(0x6A11);
		for (int i = 0; i < 500; i++) {
			if ((i & 7) == 0) {
				index.insert(null);
			} else {
				ScreenRectangle rectangle = rectangle(random, 128);
				rectangles.add(rectangle);
				index.insert(rectangle);
			}
		}

		for (int i = 0; i < 1000; i++) {
			ScreenRectangle query = rectangle(random, 256);
			boolean expected = rectangles.stream().anyMatch(rectangle -> rectangle.intersects(query));
			assertTrue(index.hasIntersection(query) == expected, query.toString());
		}

		assertFalse(index.hasIntersection(null));
	}

	private static ScreenRectangle rectangle(Random random, int range) {
		int x = random.nextInt(range * 2) - range;
		int y = random.nextInt(range * 2) - range;
		int width = random.nextInt(32) + 1;
		int height = random.nextInt(32) + 1;
		return new ScreenRectangle(x, y, width, height);
	}
}
