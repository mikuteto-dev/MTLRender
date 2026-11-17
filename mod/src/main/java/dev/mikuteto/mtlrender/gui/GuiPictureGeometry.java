package dev.mikuteto.mtlrender.gui;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

public final class GuiPictureGeometry {
    private GuiPictureGeometry() {
    }

    public static Snapshot snapshot(final BlitRenderState state) {
        Objects.requireNonNull(state, "state");
        return new Snapshot(
            new Matrix3x2f(state.pose()),
            state.x0(),
            state.y0(),
            state.x1(),
            state.y1(),
            state.u0(),
            state.v0(),
            state.u1(),
            state.v1(),
            state.color(),
            state.scissorArea(),
            state.pipeline(),
            state.textureSetup()
        );
    }

    public static Snapshot snapshot(
        final PictureInPictureRenderState state,
        final TextureSetup textureSetup
    ) {
        Objects.requireNonNull(state, "state");
        return new Snapshot(
            new Matrix3x2f(state.pose()),
            state.x0(),
            state.y0(),
            state.x1(),
            state.y1(),
            0.0F,
            1.0F,
            1.0F,
            0.0F,
            -1,
            state.scissorArea(),
            RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
            textureSetup
        );
    }

    public static GuiGeometryBatch encode(final Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        VertexFormat format = snapshot.vertexFormat();
        GuiGeometryBatch.DrawKey key = new GuiGeometryBatch.DrawKey(
            snapshot.pipeline(),
            snapshot.textureSetup(),
            snapshot.scissorArea(),
            format,
            snapshot.topology()
        );
        GuiGeometrySink sink = new GuiGeometrySink();
        sink.begin(key);
        sink.addVertexWith2DPose(snapshot.pose(), snapshot.x0(), snapshot.y0())
            .setUv(snapshot.u0(), snapshot.v0())
            .setColor(snapshot.color());
        sink.addVertexWith2DPose(snapshot.pose(), snapshot.x0(), snapshot.y1())
            .setUv(snapshot.u0(), snapshot.v1())
            .setColor(snapshot.color());
        sink.addVertexWith2DPose(snapshot.pose(), snapshot.x1(), snapshot.y1())
            .setUv(snapshot.u1(), snapshot.v1())
            .setColor(snapshot.color());
        sink.addVertexWith2DPose(snapshot.pose(), snapshot.x1(), snapshot.y0())
            .setUv(snapshot.u1(), snapshot.v0())
            .setColor(snapshot.color());
        return sink.finish();
    }

    public static List<GuiGeometryBatch> encodeInOrder(final Iterable<? extends Snapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        List<GuiGeometryBatch> result = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            result.add(encode(snapshot));
        }
        return List.copyOf(result);
    }

    public record Snapshot(
        Matrix3x2f pose,
        int x0,
        int y0,
        int x1,
        int y1,
        float u0,
        float v0,
        float u1,
        float v1,
        int color,
        @Nullable ScreenRectangle scissorArea,
        RenderPipeline pipeline,
        TextureSetup textureSetup
    ) {
        public Snapshot {
            pose = new Matrix3x2f(Objects.requireNonNull(pose, "pose"));
            pipeline = Objects.requireNonNull(pipeline, "pipeline");
            textureSetup = Objects.requireNonNull(textureSetup, "textureSetup");
        }

        public VertexFormat vertexFormat() {
            return Objects.requireNonNull(this.pipeline.getVertexFormatBinding(0), "GUI picture vertex format");
        }

        public PrimitiveTopology topology() {
            return PrimitiveTopology.QUADS;
        }
    }
}
