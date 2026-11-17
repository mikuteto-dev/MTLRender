package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Objects;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

public final class GuiItemGeometry {
    private GuiItemGeometry() {
    }

    public static Snapshot snapshot(
        final GuiItemRenderState state,
        final GuiItemAtlas.SlotView slot,
        final GpuSampler sampler
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(sampler, "sampler");
        TextureSetup textureSetup = TextureSetup.singleTexture(slot.textureView(), sampler);
        if (state.oversizedItemBounds() != null) {
            throw new IllegalArgumentException("oversized items require the picture-in-picture fallback");
        }
        return new Snapshot(
            new Matrix3x2f(state.pose()),
            state.x(),
            state.y(),
            slot.u0(),
            slot.v0(),
            slot.u1(),
            slot.v1(),
            -1,
            state.scissorArea(),
            textureSetup
        );
    }

    public static GuiGeometryBatch encode(final Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return GuiPictureGeometry.encode(new GuiPictureGeometry.Snapshot(
            snapshot.pose(),
            snapshot.x(),
            snapshot.y(),
            snapshot.x() + 16,
            snapshot.y() + 16,
            snapshot.u0(),
            snapshot.v0(),
            snapshot.u1(),
            snapshot.v1(),
            snapshot.color(),
            snapshot.scissorArea(),
            snapshot.pipeline(),
            snapshot.textureSetup()
        ));
    }

    public record Snapshot(
        Matrix3x2f pose,
        int x,
        int y,
        float u0,
        float v0,
        float u1,
        float v1,
        int color,
        @Nullable ScreenRectangle scissorArea,
        TextureSetup textureSetup
    ) {
        public Snapshot {
            pose = new Matrix3x2f(Objects.requireNonNull(pose, "pose"));
            textureSetup = Objects.requireNonNull(textureSetup, "textureSetup");
        }

        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA;
        }

        public VertexFormat vertexFormat() {
            return Objects.requireNonNull(this.pipeline().getVertexFormatBinding(0), "GUI item vertex format");
        }

        public PrimitiveTopology topology() {
            return PrimitiveTopology.QUADS;
        }
    }
}
