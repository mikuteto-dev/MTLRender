package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jspecify.annotations.Nullable;

public final class GuiPrimitiveGeometry {
	private GuiPrimitiveGeometry() {
	}

	@Nullable
	public static GuiGeometryBatch encode(final GuiElementRenderState state) {
		if (!(state instanceof ColoredRectangleRenderState) && !(state instanceof BlitRenderState)) {
			return null;
		}

		RenderPipeline pipeline = state.pipeline();
		VertexFormat format = pipeline.getVertexFormatBinding(0);
		if (format == null) {
			return null;
		}

		GuiGeometryBatch.DrawKey key = new GuiGeometryBatch.DrawKey(
			pipeline,
			state.textureSetup(),
			state.scissorArea(),
			format,
			PrimitiveTopology.QUADS
		);
		GuiGeometrySink sink = new GuiGeometrySink();
		sink.begin(key);
		state.buildVertices(sink);
		return sink.finish();
	}
}
