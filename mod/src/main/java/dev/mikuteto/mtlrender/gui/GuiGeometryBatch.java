package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import org.jspecify.annotations.Nullable;

public final class GuiGeometryBatch {
	private final List<Draw> draws;
	private final int firstDrawIndexAfterBlur;

	GuiGeometryBatch(final List<Draw> draws, final int firstDrawIndexAfterBlur) {
		this.draws = List.copyOf(draws);
		if (firstDrawIndexAfterBlur < 0 || firstDrawIndexAfterBlur > this.draws.size()) {
			throw new IllegalArgumentException("blur boundary is outside the draw list");
		}
		this.firstDrawIndexAfterBlur = firstDrawIndexAfterBlur;
	}

	public List<Draw> draws() {
		return this.draws;
	}

	public int drawCount() {
		return this.draws.size();
	}

	public boolean isEmpty() {
		return this.draws.isEmpty();
	}

	public int firstDrawIndexAfterBlur() {
		return this.firstDrawIndexAfterBlur;
	}

	public void forEachDraw(final Consumer<? super Draw> consumer) {
		this.draws.forEach(consumer);
	}

	public void replay(final VertexConsumer consumer) {
		for (Draw draw : this.draws) {
			ByteBuffer data = draw.vertices();
			VertexFormat format = draw.key().vertexFormat();
			VertexFormatElement position = format.getElement("Position");
			VertexFormatElement color = format.getElement("Color");
			VertexFormatElement uv0 = format.getElement("UV0");
			VertexFormatElement uv1 = format.getElement("UV1");
			VertexFormatElement uv2 = format.getElement("UV2");
			VertexFormatElement normal = format.getElement("Normal");
			VertexFormatElement lineWidth = format.getElement("LineWidth");
			for (int vertex = 0; vertex < draw.vertexCount(); vertex++) {
				int base = vertex * draw.key().vertexStride();
				consumer.addVertex(data.getFloat(base + position.offset()), data.getFloat(base + position.offset() + 4), data.getFloat(base + position.offset() + 8));
				if (color != null) {
					int offset = base + color.offset();
					consumer.setColor(data.get(offset) & 255, data.get(offset + 1) & 255, data.get(offset + 2) & 255, data.get(offset + 3) & 255);
				}
				if (uv0 != null) {
					int offset = base + uv0.offset();
					consumer.setUv(data.getFloat(offset), data.getFloat(offset + 4));
				}
				if (uv1 != null) {
					int offset = base + uv1.offset();
					consumer.setUv1(data.getShort(offset), data.getShort(offset + 2));
				}
				if (uv2 != null) {
					int offset = base + uv2.offset();
					consumer.setUv2(data.getShort(offset), data.getShort(offset + 2));
				}
				if (normal != null) {
					int offset = base + normal.offset();
					consumer.setNormal(data.get(offset) / 127.0F, data.get(offset + 1) / 127.0F, data.get(offset + 2) / 127.0F);
				}
				if (lineWidth != null) {
					consumer.setLineWidth(data.getFloat(base + lineWidth.offset()));
				}
			}
		}
	}

	public static final class DrawKey {
		private final RenderPipeline pipeline;
		private final TextureSetup textureSetup;
		@Nullable
		private final ScreenRectangle scissorArea;
		private final VertexFormat vertexFormat;
		private final PrimitiveTopology topology;

		public DrawKey(
			final RenderPipeline pipeline,
			final TextureSetup textureSetup,
			@Nullable final ScreenRectangle scissorArea,
			final VertexFormat vertexFormat,
			final PrimitiveTopology topology
		) {
			this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
			this.textureSetup = Objects.requireNonNull(textureSetup, "textureSetup");
			this.scissorArea = scissorArea;
			this.vertexFormat = Objects.requireNonNull(vertexFormat, "vertexFormat");
			this.topology = Objects.requireNonNull(topology, "topology");
			if (!vertexFormat.contains("Position")) {
				throw new IllegalArgumentException("GUI geometry needs a position attribute");
			}
		}

		public RenderPipeline pipeline() {
			return this.pipeline;
		}

		public TextureSetup textureSetup() {
			return this.textureSetup;
		}

		@Nullable
		public ScreenRectangle scissorArea() {
			return this.scissorArea;
		}

		public VertexFormat vertexFormat() {
			return this.vertexFormat;
		}

		public PrimitiveTopology topology() {
			return this.topology;
		}

		public int vertexStride() {
			return this.vertexFormat.getVertexSize();
		}

		@Override
		public boolean equals(final Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof DrawKey other)) {
				return false;
			}
			return this.pipeline == other.pipeline
				&& this.textureSetup.equals(other.textureSetup)
				&& Objects.equals(this.scissorArea, other.scissorArea)
				&& this.vertexFormat.equals(other.vertexFormat)
				&& this.topology == other.topology;
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(this.pipeline);
			result = 31 * result + this.textureSetup.hashCode();
			result = 31 * result + Objects.hashCode(this.scissorArea);
			result = 31 * result + this.vertexFormat.hashCode();
			return 31 * result + this.topology.hashCode();
		}
	}

	public static final class Draw {
		private final DrawKey key;
		private final ByteBuffer vertices;
		private final int vertexCount;

		Draw(final DrawKey key, final ByteBuffer vertices, final int vertexCount) {
			this.key = Objects.requireNonNull(key, "key");
			ByteBuffer source = Objects.requireNonNull(vertices, "vertices");
			this.vertexCount = vertexCount;
			if (vertexCount < 0) {
				throw new IllegalArgumentException("vertexCount must not be negative");
			}
			long expectedBytes = (long)key.vertexStride() * vertexCount;
			if (expectedBytes != source.remaining()) {
				throw new IllegalArgumentException("vertex data does not match vertex count");
			}
			source = source.duplicate();
			byte[] data = new byte[source.remaining()];
			source.get(data);
			this.vertices = ByteBuffer.wrap(data).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
		}

		public DrawKey key() {
			return this.key;
		}

		public ByteBuffer vertices() {
			return this.vertices.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		}

		public int vertexCount() {
			return this.vertexCount;
		}

		public int byteLength() {
			return this.vertices.remaining();
		}
	}
}
