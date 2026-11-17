package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class GuiTextGeometry {
    private GuiTextGeometry() {
    }

    public static Snapshot snapshot(final GlyphRenderState state) {
        TextRenderable renderable = state.renderable();
        TextureSetup textureSetup = TextureSetup.singleTextureWithLightmap(
            renderable.textureView(),
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        );
        return new Snapshot(
            state.pose(),
            renderable,
            state.pipeline(),
            textureSetup,
            state.scissorArea()
        );
    }

    public static GuiGeometryBatch encode(final Snapshot snapshot) {
        RenderPipeline pipeline = snapshot.pipeline();
        var format = pipeline.getVertexFormatBinding(0);
        if (format == null) {
            return null;
        }
        GuiGeometryBatch.DrawKey key = new GuiGeometryBatch.DrawKey(
            pipeline,
            snapshot.textureSetup(),
            snapshot.scissorArea(),
            format,
            PrimitiveTopology.QUADS
        );
        GuiGeometrySink sink = new GuiGeometrySink();
        sink.begin(key);
        snapshot.renderable().render(new Matrix4f().mul(snapshot.pose()), sink, 15728880, true);
        GuiGeometryBatch batch = sink.finish();
        if (batch.drawCount() == 1 && batch.draws().get(0).vertexCount() == 0) {
            return new GuiGeometryBatch(java.util.List.of(), 0);
        }
        return batch;
    }

    public record Snapshot(
        Matrix3x2fc pose,
        TextRenderable renderable,
        RenderPipeline pipeline,
        TextureSetup textureSetup,
        @Nullable ScreenRectangle scissorArea
    ) {    }
}
