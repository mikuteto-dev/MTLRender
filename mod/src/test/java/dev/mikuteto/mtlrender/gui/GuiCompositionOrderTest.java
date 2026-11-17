package dev.mikuteto.mtlrender.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.render.TextureSetup;
import org.junit.jupiter.api.Test;

final class GuiCompositionOrderTest {
	@Test
	void gameRendererCompletesCompositionBeforeSwitchingToNativeGuiTarget() throws IOException {
		String source = readSource("dev/mikuteto/mtlrender/mixin/GameRendererMixin.java");

		assertInOrder(source, List.of(
			"MetalFx.begin((GameRenderer)",
			"FogRenderer;endFrame()V",
			"MetalFx.finish3d(this.mainRenderTarget)",
			"MetalFx.nativeGuiTarget(this.mainRenderTarget)"
		));
	}

	@Test
	void guiRendererUsesCompositionTargetAndNativeViewportWithoutRecomposing() throws IOException {
		String source = readSource("dev/mikuteto/mtlrender/mixin/GuiRendererMixin.java");

		assertTrue(source.contains("composition.nativeTarget()"));
		assertTrue(source.contains("GuiNativeResolution.viewport("));
		assertTrue(source.contains("renderPass.enableScissor(scissor.left(), scissor.bottom(), scissor.width(), scissor.height())"));
		assertTrue(!source.contains("MetalFx.finish3d"));
	}

	@Test
	void preparedGuiDrawsRetainStrataOrderAcrossBlurBoundary() {
		GuiGeometryBatch before = batch(1.0F);
		GuiGeometryBatch after = batch(2.0F);
		GuiPreparedFrame frame = GuiPreparedFrame.capture(
			new GuiFrameCacheKey(320, 180, 1.0F, 1L, 2L, 3L, 4, 5, 6L, 7L, false, 0.0F, 0.0F, 0.0F, 1.0F, 8L, 9L, 10L),
			List.of(before, after),
			1
		);

		assertTrue(frame.draws().get(0).vertices().getFloat(0) < frame.draws().get(1).vertices().getFloat(0));
		assertTrue(frame.firstDrawIndexAfterBlur() > 0);
	}

	private static String readSource(final String relative) throws IOException {
		Path direct = Path.of("src/main/java", relative);
		Path fromRoot = Path.of("mod/src/main/java", relative);
		return Files.readString(Files.exists(direct) ? direct : fromRoot);
	}

	private static GuiGeometryBatch batch(final float x) {
		GuiGeometrySink sink = new GuiGeometrySink();
		sink.begin(new GuiGeometryBatch.DrawKey(
			RenderPipelines.GUI,
			TextureSetup.noTexture(),
			null,
			DefaultVertexFormat.POSITION_COLOR,
			PrimitiveTopology.QUADS
		));
		sink.addVertex(x, 0.0F, 0.0F).setColor(0xFFFFFFFF);
		return sink.finish();
	}

	private static void assertInOrder(final String source, final List<String> fragments) {
		int previous = -1;
		for (String fragment : fragments) {
			int current = source.indexOf(fragment);
			assertTrue(current > previous, fragment);
			previous = current;
		}
	}
}
