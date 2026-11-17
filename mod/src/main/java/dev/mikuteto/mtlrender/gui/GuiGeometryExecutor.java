package dev.mikuteto.mtlrender.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

public final class GuiGeometryExecutor implements AutoCloseable {
	private final ExecutorService workers;
	private final AtomicLong nextGeneration = new AtomicLong();
	private volatile long cancelledGeneration;

	public GuiGeometryExecutor() {
		this(workerCount());
	}

	public static boolean enabled() {
		return !"false".equals(System.getProperty("mtlrender.guiParallel"));
	}

	GuiGeometryExecutor(final int workerCount) {
		if (workerCount < 1) {
			throw new IllegalArgumentException("workerCount must be positive");
		}
		this.workers = Executors.newFixedThreadPool(workerCount, new WorkerThreadFactory());
	}

	public Frame beginFrame() {
		if (!enabled()) {
			throw new IllegalStateException("parallel GUI geometry is disabled");
		}
		long generation = this.nextGeneration.incrementAndGet();
		this.cancelledGeneration = generation - 1;
		return new Frame(generation);
	}

	public void cancel() {
		this.cancelledGeneration = this.nextGeneration.incrementAndGet();
	}

	public long generation() {
		return this.nextGeneration.get();
	}

	@Override
	public void close() {
		this.cancel();
		this.workers.shutdownNow();
	}

	private static int workerCount() {
		int available = Runtime.getRuntime().availableProcessors();
		String configured = System.getProperty("mtlrender.guiWorkers");
		if (configured == null) {
			return Math.min(8, Math.max(1, available - 1));
		}
		try {
			return Math.min(8, Math.max(1, Integer.parseInt(configured)));
		} catch (NumberFormatException ignored) {
			return Math.min(8, Math.max(1, available - 1));
		}
	}

	public final class Frame {
		private final long generation;
		private final List<Future<GuiGeometryBatch>> results = new ArrayList<>();
		private boolean submitted;

		private Frame(final long generation) {
			this.generation = generation;
		}

		public long generation() {
			return this.generation;
		}

		public void submit(final GuiElementRenderState state) {
			this.submit(state, null);
		}

		public void submit(final GuiElementRenderState state, final GuiItemGeometry.Snapshot itemSnapshot) {
			if (this.submitted) {
				throw new IllegalStateException("GUI frame has been awaited");
			}
			if (itemSnapshot != null) {
				this.results.add(GuiGeometryExecutor.this.workers.submit(() -> GuiItemGeometry.encode(itemSnapshot)));
			} else if (state instanceof GlyphRenderState glyph) {
				GuiTextGeometry.Snapshot snapshot = GuiTextGeometry.snapshot(glyph);
				this.results.add(GuiGeometryExecutor.this.workers.submit(() -> GuiTextGeometry.encode(snapshot)));
			} else {
				this.results.add(GuiGeometryExecutor.this.workers.submit(() -> GuiPrimitiveGeometry.encode(state)));
			}
		}

		public void submitPicture(final GuiElementRenderState state, final GuiPictureGeometry.Snapshot pictureSnapshot) {
			if (this.submitted) {
				throw new IllegalStateException("GUI frame has been awaited");
			}
			this.results.add(GuiGeometryExecutor.this.workers.submit(() -> GuiPictureGeometry.encode(pictureSnapshot)));
		}

		public OrderedResult await() {
			this.submitted = true;
			if (this.generation <= GuiGeometryExecutor.this.cancelledGeneration) {
				cancelResults();
				return cancelledResult();
			}

			List<GuiGeometryBatch> batches = new ArrayList<>();
			List<GuiGeometryBatch> ordered = new ArrayList<>();
			List<Integer> fallback = new ArrayList<>();
			List<Integer> primitiveIndexes = new ArrayList<>();
			for (int index = 0; index < this.results.size(); index++) {
				try {
					GuiGeometryBatch batch = this.results.get(index).get();
					ordered.add(batch);
					if (batch == null) {
						fallback.add(index);
					} else {
						primitiveIndexes.add(index);
						batches.add(batch);
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					cancelResults();
					return cancelledResult();
				} catch (ExecutionException | CancellationException exception) {
					ordered.add(null);
					fallback.add(index);
				}
			}
			return new OrderedResult(this.generation, batches, ordered, false, fallback, primitiveIndexes);
		}

		private OrderedResult cancelledResult() {
			List<GuiGeometryBatch> ordered = new ArrayList<>(this.results.size());
			List<Integer> fallback = new ArrayList<>(this.results.size());
			for (int index = 0; index < this.results.size(); index++) {
				ordered.add(null);
				fallback.add(index);
			}
			return new OrderedResult(this.generation, List.of(), ordered, true, fallback, List.of());
		}

		private void cancelResults() {
			for (Future<GuiGeometryBatch> result : this.results) {
				result.cancel(true);
			}
		}
	}

	public static final class OrderedResult {
		private final long generation;
		private final List<GuiGeometryBatch> batches;
		private final List<GuiGeometryBatch> orderedBatches;
		private final List<Integer> fallbackIndexes;
		private final List<Integer> primitiveIndexes;
		private final boolean cancelled;

		private OrderedResult(
			final long generation,
			final List<GuiGeometryBatch> batches,
			final List<GuiGeometryBatch> orderedBatches,
			final boolean cancelled
		) {
			this(generation, batches, orderedBatches, cancelled, List.of(), List.of());
		}

		private OrderedResult(
			final long generation,
			final List<GuiGeometryBatch> batches,
			final List<GuiGeometryBatch> orderedBatches,
			final boolean cancelled,
			final List<Integer> fallbackIndexes,
			final List<Integer> primitiveIndexes
		) {
			this.generation = generation;
			this.batches = List.copyOf(batches);
			this.orderedBatches = Collections.unmodifiableList(new ArrayList<>(orderedBatches));
			this.cancelled = cancelled;
			this.fallbackIndexes = List.copyOf(fallbackIndexes);
			this.primitiveIndexes = List.copyOf(primitiveIndexes);
		}

		public long generation() {
			return this.generation;
		}

		public List<GuiGeometryBatch> batches() {
			return this.batches;
		}

		public List<GuiGeometryBatch> orderedBatches() {
			return this.orderedBatches;
		}

		public GuiGeometryBatch batchAt(final int index) {
			return this.orderedBatches.get(index);
		}

		public List<Integer> fallbackIndexes() {
			return this.fallbackIndexes;
		}

		public List<Integer> primitiveIndexes() {
			return this.primitiveIndexes;
		}

		public boolean cancelled() {
			return this.cancelled;
		}
	}

	private static final class WorkerThreadFactory implements ThreadFactory {
		private final AtomicLong sequence = new AtomicLong();

		@Override
		public Thread newThread(final Runnable runnable) {
			Thread thread = new Thread(runnable, "MTLRender-Gui-" + this.sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
