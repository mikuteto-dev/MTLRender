package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import org.jspecify.annotations.Nullable;

public final class GuiPreparedDraw {
	private final GuiGeometryBatch.Draw draw;
	private final long pipelineIdentity;
	private final long textureIdentity;
	private final List<Long> textureIdentities;
	private final List<Long> samplerIdentities;

	GuiPreparedDraw(final GuiGeometryBatch.Draw draw) {
		this.draw = draw;
		this.pipelineIdentity = Integer.toUnsignedLong(System.identityHashCode(draw.key().pipeline()));
		this.textureIdentity = Integer.toUnsignedLong(System.identityHashCode(draw.key().textureSetup()));
		TextureSetup setup = draw.key().textureSetup();
		this.textureIdentities = List.of(identity(setup.texure0()), identity(setup.texure1()), identity(setup.texure2()));
		this.samplerIdentities = List.of(identity(setup.sampler0()), identity(setup.sampler1()), identity(setup.sampler2()));
	}

	public RenderPipeline pipeline() {
		return this.draw.key().pipeline();
	}

	public TextureSetup textureSetup() {
		return this.draw.key().textureSetup();
	}

	@Nullable
	public ScreenRectangle scissorArea() {
		return this.draw.key().scissorArea();
	}

	public VertexFormat vertexFormat() {
		return this.draw.key().vertexFormat();
	}

	public PrimitiveTopology topology() {
		return this.draw.key().topology();
	}

	public ByteBuffer vertices() {
		return this.draw.vertices();
	}

	public int vertexCount() {
		return this.draw.vertexCount();
	}

	public int byteLength() {
		return this.draw.byteLength();
	}

	public long pipelineIdentity() {
		return this.pipelineIdentity;
	}

	public long textureIdentity() {
		return this.textureIdentity;
	}

	public List<Long> textureIdentities() {
		return this.textureIdentities;
	}

	public List<Long> samplerIdentities() {
		return this.samplerIdentities;
	}

	private static long identity(final Object value) {
		return value == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(value));
	}

	public void replay(final VertexConsumer consumer) {
		GuiGeometryBatch single = new GuiGeometryBatch(java.util.List.of(this.draw), 1);
		single.replay(consumer);
	}
}
