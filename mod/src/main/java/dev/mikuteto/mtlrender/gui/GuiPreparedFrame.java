package dev.mikuteto.mtlrender.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GuiPreparedFrame {
	private final GuiFrameCacheKey cacheKey;
	private final List<GuiGeometryBatch> batches;
	private final List<GuiPreparedDraw> draws;
	private final int firstDrawIndexAfterBlur;
	private final long resourceGeneration;

	private GuiPreparedFrame(
		final GuiFrameCacheKey cacheKey,
		final List<GuiGeometryBatch> batches,
		final List<GuiPreparedDraw> draws,
		final int firstDrawIndexAfterBlur
	) {
		this.cacheKey = Objects.requireNonNull(cacheKey, "cacheKey");
		this.batches = List.copyOf(batches);
		this.draws = List.copyOf(draws);
		if (firstDrawIndexAfterBlur < 0 || firstDrawIndexAfterBlur > this.draws.size()) {
			throw new IllegalArgumentException("blur boundary is outside the draw list");
		}
		this.firstDrawIndexAfterBlur = firstDrawIndexAfterBlur;
		this.resourceGeneration = cacheKey.resourceGeneration();
	}

	public static GuiPreparedFrame capture(
		final GuiFrameCacheKey cacheKey,
		final List<GuiGeometryBatch> batches,
		final int firstBatchAfterBlur
	) {
		Objects.requireNonNull(cacheKey, "cacheKey");
		Objects.requireNonNull(batches, "batches");
		if (firstBatchAfterBlur < 0 || firstBatchAfterBlur > batches.size()) {
			throw new IllegalArgumentException("blur boundary is outside the batch list");
		}
		List<GuiPreparedDraw> draws = new ArrayList<>();
		int firstDrawAfterBlur = 0;
		for (int index = 0; index < batches.size(); index++) {
			GuiGeometryBatch batch = Objects.requireNonNull(batches.get(index), "batch");
			if (index == firstBatchAfterBlur) {
				firstDrawAfterBlur = draws.size();
			}
			for (GuiGeometryBatch.Draw draw : batch.draws()) {
				draws.add(new GuiPreparedDraw(draw));
			}
		}
		if (firstBatchAfterBlur == batches.size()) {
			firstDrawAfterBlur = draws.size();
		}
		return new GuiPreparedFrame(cacheKey, batches, draws, firstDrawAfterBlur);
	}

	public static GuiPreparedFrame empty(final GuiFrameCacheKey cacheKey) {
		return new GuiPreparedFrame(cacheKey, List.of(), List.of(), 0);
	}

	public GuiFrameCacheKey cacheKey() {
		return this.cacheKey;
	}

	public List<GuiPreparedDraw> draws() {
		return this.draws;
	}

	public GuiGeometryBatch batchAt(final int index) {
		return this.batches.get(index);
	}

	public int firstDrawIndexAfterBlur() {
		return this.firstDrawIndexAfterBlur;
	}

	public long resourceGeneration() {
		return this.resourceGeneration;
	}

	public boolean isEmpty() {
		return this.draws.isEmpty();
	}
}
