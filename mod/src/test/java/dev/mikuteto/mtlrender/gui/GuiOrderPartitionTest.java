package dev.mikuteto.mtlrender.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

final class GuiOrderPartitionTest {
	@Test
	void nestedAndIntersectingElementsKeepVanillaTreeTraversalOrder() {
		GuiRenderState state = new GuiRenderState();
		GuiElementRenderState root = rectangle(0, 0, 100, 100, null);
		GuiElementRenderState nested = rectangle(10, 10, 20, 20, null);
		GuiElementRenderState intersecting = rectangle(15, 15, 20, 20, null);
		GuiElementRenderState sibling = rectangle(200, 0, 20, 20, null);

		state.addGuiElement(root);
		state.addGuiElement(nested);
		state.addGuiElement(intersecting);
		state.addGuiElement(sibling);

		assertEquals(List.of(root, sibling, nested, intersecting), elements(state, GuiRenderState.TraverseRange.ALL));
	}

	@Test
	void blurRangesPartitionStrataWithoutCrossingTheBoundary() {
		GuiRenderState state = new GuiRenderState();
		GuiElementRenderState before = rectangle(0, 0, 10, 10, null);
		GuiElementRenderState blurredBackground = rectangle(20, 0, 10, 10, null);
		GuiElementRenderState after = rectangle(40, 0, 10, 10, null);

		state.addGuiElement(before);
		state.nextStratum();
		state.addGuiElement(blurredBackground);
		state.blurBeforeThisStratum();
		state.nextStratum();
		state.addGuiElement(after);

		assertEquals(List.of(before), elements(state, GuiRenderState.TraverseRange.BEFORE_BLUR));
		assertEquals(List.of(blurredBackground, after), elements(state, GuiRenderState.TraverseRange.AFTER_BLUR));
	}

	@Test
	void atlasReplacementKeepsInsertionOrderAndScissorPartition() {
		GuiRenderState state = new GuiRenderState();
		ScreenRectangle scissor = new ScreenRectangle(0, 0, 32, 32);
		GuiElementRenderState original = rectangle(2, 2, 10, 10, scissor);
		BlitRenderState replacement = blit(2, 2, 10, 10, scissor);
		GuiElementRenderState intersecting = rectangle(6, 6, 10, 10, scissor);

		state.addGuiElement(original);
		state.addBlitToCurrentLayer(replacement);
		state.addGuiElement(intersecting);

		assertEquals(List.of(original, replacement, intersecting), elements(state, GuiRenderState.TraverseRange.ALL));
		GuiGeometrySink sink = new GuiGeometrySink();
		GuiGeometryBatch.DrawKey first = key(null);
		GuiGeometryBatch.DrawKey second = key(new ScreenRectangle(1, 1, 20, 20));
		sink.begin(first);
		sink.separate();
		sink.begin(second);
		GuiGeometryBatch batch = sink.finish();
		assertEquals(List.of(first, second), batch.draws().stream().map(GuiGeometryBatch.Draw::key).toList());
	}

	@Test
	void blurCanOnlyBeDeclaredOnce() {
		GuiRenderState state = new GuiRenderState();
		state.blurBeforeThisStratum();
		assertThrows(IllegalStateException.class, state::blurBeforeThisStratum);
	}

	private static List<GuiElementRenderState> elements(final GuiRenderState state, final GuiRenderState.TraverseRange range) {
		List<GuiElementRenderState> result = new ArrayList<>();
		state.forEachElement(result::add, range);
		return result;
	}

	private static GuiElementRenderState rectangle(final int x, final int y, final int width, final int height, final ScreenRectangle scissor) {
		return new ColoredRectangleRenderState(
			RenderPipelines.GUI,
			TextureSetup.noTexture(),
			new Matrix3x2f(),
			x,
			y,
			x + width,
			y + height,
			0xFFFFFFFF,
			0xFFFFFFFF,
			scissor
		);
	}

	private static BlitRenderState blit(final int x, final int y, final int width, final int height, final ScreenRectangle scissor) {
		return new BlitRenderState(
			RenderPipelines.GUI_TEXTURED,
			TextureSetup.noTexture(),
			new Matrix3x2f(),
			x,
			y,
			x + width,
			y + height,
			0.0F,
			1.0F,
			0.0F,
			1.0F,
			-1,
			scissor
		);
	}

	private static GuiGeometryBatch.DrawKey key(final ScreenRectangle scissor) {
		return new GuiGeometryBatch.DrawKey(
			RenderPipelines.GUI,
			TextureSetup.noTexture(),
			scissor,
			DefaultVertexFormat.POSITION_COLOR,
			PrimitiveTopology.QUADS
		);
	}
}
