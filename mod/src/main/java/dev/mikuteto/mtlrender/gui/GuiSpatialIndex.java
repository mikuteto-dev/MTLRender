package dev.mikuteto.mtlrender.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import org.jspecify.annotations.Nullable;

public final class GuiSpatialIndex {
	private static final int CELL_SHIFT = 5;
	private static final long MAX_CELLS_PER_RECTANGLE = 4096L;
	private static final long CELL_SIZE = 1L << CELL_SHIFT;

	private final Map<Long, List<ScreenRectangle>> cells = new HashMap<>();
	private final List<ScreenRectangle> largeRectangles = new ArrayList<>();

	public void insert(@Nullable ScreenRectangle bounds) {
		if (bounds == null) {
			return;
		}

		CellRange range = CellRange.create(bounds);
		if (range == null) {
			this.largeRectangles.add(bounds);
			return;
		}

		for (long y = range.minY(); y <= range.maxY(); y++) {
			for (long x = range.minX(); x <= range.maxX(); x++) {
				long key = key(x, y);
				this.cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(bounds);
			}
		}
	}

	public void reset() {
		this.cells.clear();
		this.largeRectangles.clear();
	}

	public boolean hasIntersection(@Nullable ScreenRectangle bounds) {
		if (bounds == null) {
			return false;
		}

		for (ScreenRectangle existing : this.largeRectangles) {
			if (existing.intersects(bounds)) {
				return true;
			}
		}

		CellRange range = CellRange.create(bounds);
		if (range == null) {
			for (List<ScreenRectangle> bucket : this.cells.values()) {
				for (ScreenRectangle existing : bucket) {
					if (existing.intersects(bounds)) {
						return true;
					}
				}
			}
			return false;
		}

		for (long y = range.minY(); y <= range.maxY(); y++) {
			for (long x = range.minX(); x <= range.maxX(); x++) {
				List<ScreenRectangle> bucket = this.cells.get(key(x, y));
				if (bucket == null) {
					continue;
				}
				for (ScreenRectangle existing : bucket) {
					if (existing.intersects(bounds)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static long key(long x, long y) {
		return (x << 32) ^ (y & 0xffffffffL);
	}

	private record CellRange(long minX, long maxX, long minY, long maxY) {
		@Nullable
		private static CellRange create(ScreenRectangle bounds) {
			long left = bounds.left();
			long right = bounds.right();
			long top = bounds.top();
			long bottom = bounds.bottom();
			if (right <= left || bottom <= top) {
				return null;
			}

			long minX = Math.floorDiv(left, CELL_SIZE);
			long maxX = Math.floorDiv(right - 1L, CELL_SIZE);
			long minY = Math.floorDiv(top, CELL_SIZE);
			long maxY = Math.floorDiv(bottom - 1L, CELL_SIZE);
			long cellWidth = maxX - minX + 1L;
			long cellHeight = maxY - minY + 1L;
			if (cellWidth > MAX_CELLS_PER_RECTANGLE / cellHeight) {
				return null;
			}
			return new CellRange(minX, maxX, minY, maxY);
		}
	}

	public static final class IndexedStates<T extends ScreenArea> extends ArrayList<T> {
		private final GuiSpatialIndex index;

		public IndexedStates(GuiSpatialIndex index) {
			this.index = index;
		}

		public boolean hasIntersection(@Nullable ScreenRectangle bounds) {
			return this.index.hasIntersection(bounds);
		}

		@Override
		public boolean add(T state) {
			boolean added = super.add(state);
			if (added) {
				this.index.insert(state.bounds());
			}
			return added;
		}

		@Override
		public void add(int position, T state) {
			super.add(position, state);
			this.index.insert(state.bounds());
		}

		@Override
		public T set(int position, T state) {
			T previous = super.set(position, state);
			this.index.insert(state.bounds());
			return previous;
		}
	}
}
