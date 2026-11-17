package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;

public final class GuiGeometrySink implements VertexConsumer {
	private static final int POSITION = 0;
	private static final int COLOR = 1;
	private static final int UV0 = 2;
	private static final int UV1 = 3;
	private static final int UV2 = 4;
	private static final int NORMAL = 5;
	private static final int LINE_WIDTH = 6;
	private static final String[] ATTRIBUTES = {"Position", "Color", "UV0", "UV1", "UV2", "Normal", "LineWidth"};

	private final List<GuiGeometryBatch.Draw> draws = new ArrayList<>();
	private DrawBuilder current;
	private int firstDrawIndexAfterBlur = -1;
	private boolean finished;

	public void begin(final GuiGeometryBatch.DrawKey key) {
		this.ensureOpen();
		if (this.current == null || !this.current.key().equals(key)) {
			this.finishCurrent();
			this.current = new DrawBuilder(key);
		}
	}

	public void separate() {
		this.ensureOpen();
		this.finishCurrent();
	}

	public void markBlurBoundary() {
		this.ensureOpen();
		if (this.firstDrawIndexAfterBlur >= 0) {
			throw new IllegalStateException("GUI blur boundary has already been marked");
		}
		this.finishCurrent();
		this.firstDrawIndexAfterBlur = this.draws.size();
	}

	public GuiGeometryBatch finish() {
		this.ensureOpen();
		this.finishCurrent();
		this.finished = true;
		int boundary = this.firstDrawIndexAfterBlur >= 0 ? this.firstDrawIndexAfterBlur : this.draws.size();
		return new GuiGeometryBatch(this.draws, boundary);
	}

	public void reset() {
		this.draws.clear();
		this.current = null;
		this.firstDrawIndexAfterBlur = -1;
		this.finished = false;
	}

	@Override
	public VertexConsumer addVertex(final float x, final float y, final float z) {
		DrawBuilder draw = this.requireCurrent();
		draw.beginVertex();
		draw.putFloat(POSITION, 0, x);
		draw.putFloat(POSITION, 1, y);
		draw.putFloat(POSITION, 2, z);
		return this;
	}

	@Override
	public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
		DrawBuilder draw = this.requireCurrent();
		VertexFormatElement element = draw.element(COLOR);
		if (element != null && draw.writable(COLOR)) {
			draw.putByte(element, 0, r);
			draw.putByte(element, 1, g);
			draw.putByte(element, 2, b);
			draw.putByte(element, 3, a);
			draw.fill(COLOR);
		}
		return this;
	}

	@Override
	public VertexConsumer setColor(final int color) {
		DrawBuilder draw = this.requireCurrent();
		VertexFormatElement element = draw.element(COLOR);
		if (element != null && draw.writable(COLOR)) {
			draw.putByte(element, 0, color >> 16);
			draw.putByte(element, 1, color >> 8);
			draw.putByte(element, 2, color);
			draw.putByte(element, 3, color >> 24);
			draw.fill(COLOR);
		}
		return this;
	}

	@Override
	public VertexConsumer setUv(final float u, final float v) {
		DrawBuilder draw = this.requireCurrent();
		VertexFormatElement element = draw.element(UV0);
		if (element != null && draw.writable(UV0)) {
			draw.putFloat(element, 0, u);
			draw.putFloat(element, 1, v);
			draw.fill(UV0);
		}
		return this;
	}

	@Override
	public VertexConsumer setUv1(final int u, final int v) {
		return this.setShorts(UV1, u, v);
	}

	@Override
	public VertexConsumer setUv2(final int u, final int v) {
		return this.setShorts(UV2, u, v);
	}

	@Override
	public VertexConsumer setNormal(final float x, final float y, final float z) {
		DrawBuilder draw = this.requireCurrent();
		VertexFormatElement element = draw.element(NORMAL);
		if (element != null && draw.writable(NORMAL)) {
			draw.putByte(element, 0, normalIntValue(x));
			draw.putByte(element, 1, normalIntValue(y));
			draw.putByte(element, 2, normalIntValue(z));
			draw.fill(NORMAL);
		}
		return this;
	}

	@Override
	public VertexConsumer setLineWidth(final float width) {
		DrawBuilder draw = this.requireCurrent();
		VertexFormatElement element = draw.element(LINE_WIDTH);
		if (element != null && draw.writable(LINE_WIDTH)) {
			draw.putFloat(element, 0, width);
			draw.fill(LINE_WIDTH);
		}
		return this;
	}

	private VertexConsumer setShorts(final int semantic, final int u, final int v) {
		DrawBuilder draw = this.requireCurrent();
		VertexFormatElement element = draw.element(semantic);
		if (element != null && draw.writable(semantic)) {
			draw.putShort(element, 0, (short)u);
			draw.putShort(element, 1, (short)v);
			draw.fill(semantic);
		}
		return this;
	}

	private DrawBuilder requireCurrent() {
		this.ensureOpen();
		if (this.current == null) {
			throw new IllegalStateException("No GUI draw has been started");
		}
		return this.current;
	}

	private void finishCurrent() {
		if (this.current != null) {
			this.current.finish();
			this.draws.add(this.current.build());
			this.current = null;
		}
	}

	private void ensureOpen() {
		if (this.finished) {
			throw new IllegalStateException("GUI geometry sink has been finished");
		}
	}

	private static byte normalIntValue(final float value) {
		return (byte)((int)(Mth.clamp(value, -1.0F, 1.0F) * 127.0F) & 255);
	}

	private static final class DrawBuilder {
		private final GuiGeometryBatch.DrawKey key;
		private final ByteArrayOutputStream data = new ByteArrayOutputStream();
		private final int requiredAttributes;
		private byte[] vertex;
		private int attributesToFill;
		private int vertexCount;

		private DrawBuilder(final GuiGeometryBatch.DrawKey key) {
			this.key = key;
			int mask = 0;
			for (int semantic = 0; semantic < ATTRIBUTES.length; semantic++) {
				if (this.key.vertexFormat().getElement(ATTRIBUTES[semantic]) != null) {
					mask |= 1 << semantic;
				}
			}
			this.requiredAttributes = mask;
		}

		private GuiGeometryBatch.DrawKey key() {
			return this.key;
		}

		private VertexFormatElement element(final int semantic) {
			return this.key.vertexFormat().getElement(ATTRIBUTES[semantic]);
		}

		private void beginVertex() {
			this.finishVertex();
			this.vertex = new byte[this.key.vertexStride()];
			this.attributesToFill = this.requiredAttributes & ~(1 << POSITION);
			this.vertexCount++;
		}

		private void finishVertex() {
			if (this.vertex == null) {
				return;
			}
			if (this.attributesToFill != 0) {
				throw new IllegalStateException("Missing GUI vertex attributes");
			}
			this.data.writeBytes(this.vertex);
			this.vertex = null;
		}

		private void finish() {
			this.finishVertex();
		}

		private GuiGeometryBatch.Draw build() {
			ByteBuffer bytes = ByteBuffer.wrap(this.data.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
			return new GuiGeometryBatch.Draw(this.key, bytes, this.vertexCount);
		}

		private void requireOpenVertex() {
			if (this.vertex == null) {
				throw new IllegalArgumentException("Not currently building vertex");
			}
		}

		private boolean writable(final int semantic) {
			this.requireOpenVertex();
			return (this.attributesToFill & (1 << semantic)) != 0;
		}

		private void fill(final int semantic) {
			this.attributesToFill &= ~(1 << semantic);
		}

		private void putFloat(final int semantic, final int component, final float value) {
			VertexFormatElement element = this.element(semantic);
			if (element == null) {
				throw new IllegalStateException("Missing GUI position attribute");
			}
			this.putFloat(element, component, value);
		}

		private void putFloat(final VertexFormatElement element, final int component, final float value) {
			this.requireOpenVertex();
			int offset = element.offset() + component * Float.BYTES;
			ByteBuffer.wrap(this.vertex).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value);
		}

		private void putShort(final VertexFormatElement element, final int component, final short value) {
			this.requireOpenVertex();
			int offset = element.offset() + component * Short.BYTES;
			ByteBuffer.wrap(this.vertex).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value);
		}

		private void putByte(final VertexFormatElement element, final int component, final int value) {
			this.requireOpenVertex();
			this.vertex[element.offset() + component] = (byte)value;
		}
	}
}
