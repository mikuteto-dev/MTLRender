package dev.mikuteto.mtlrender.mixin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.backend.MetalFx;
import dev.mikuteto.mtlrender.config.Config;
import dev.mikuteto.mtlrender.debug.AbSwitch;
import dev.mikuteto.mtlrender.debug.GuiFrameCacheStats;
import dev.mikuteto.mtlrender.gui.GuiGeometryBatch;
import dev.mikuteto.mtlrender.gui.GuiGeometryExecutor;
import dev.mikuteto.mtlrender.gui.GuiItemGeometry;
import dev.mikuteto.mtlrender.gui.GuiMapAtlas;
import dev.mikuteto.mtlrender.gui.GuiNativeResolution;
import dev.mikuteto.mtlrender.gui.GuiPictureGeometry;
import dev.mikuteto.mtlrender.gui.GuiFrameCache;
import dev.mikuteto.mtlrender.gui.GuiFrameCacheKey;
import dev.mikuteto.mtlrender.gui.GuiPreparedFrame;
import dev.mikuteto.mtlrender.gui.GuiRenderSnapshot;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.pip.OversizedItemRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.OversizedItemRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
	@Unique
	private final GuiGeometryExecutor mtlrender$guiGeometryExecutor = new GuiGeometryExecutor();
	@Unique
	private GuiGeometryExecutor.Frame mtlrender$guiFrame;
	@Unique
	private GuiGeometryExecutor.OrderedResult mtlrender$guiResult;
	@Unique
	private int mtlrender$guiElementIndex;
	@Unique
	private int mtlrender$firstGuiElementAfterBlur;
	@Unique
	private final GuiFrameCache<GuiPreparedFrame> mtlrender$preparedFrames = new GuiFrameCache<>();
	@Unique
	private GuiPreparedFrame mtlrender$replayFrame;
	@Unique
	private GuiFrameCache.Lease<GuiPreparedFrame> mtlrender$replayLease;
	@Unique
	private long mtlrender$lifecycleGeneration = -1L;
	@Unique
	private final Map<GuiElementRenderState, GuiItemGeometry.Snapshot> mtlrender$itemSnapshots = new IdentityHashMap<>();
	@Unique
	private final Map<GuiElementRenderState, GuiPictureGeometry.Snapshot> mtlrender$pictureSnapshots = new IdentityHashMap<>();
	@Unique
	private final List<GuiPictureGeometry.Snapshot> mtlrender$pendingPictureSnapshots = new ArrayList<>();
	@Unique
	private GuiItemGeometry.Snapshot mtlrender$pendingItemSnapshot;
	@Shadow
	private GuiRenderState renderState;

	@Inject(method = "prepare", at = @At("HEAD"))
	private void mtlrender$beginGuiGeometryFrame(final CallbackInfo callbackInfo) {
		GuiFrameCacheStats.recordGuiUpdate();
		this.mtlrender$guiResult = null;
		this.mtlrender$replayFrame = null;
		this.mtlrender$closeReplayLease();
		this.mtlrender$guiElementIndex = 0;
		this.mtlrender$firstGuiElementAfterBlur = -1;
		this.mtlrender$itemSnapshots.clear();
		this.mtlrender$pictureSnapshots.clear();
		this.mtlrender$pendingPictureSnapshots.clear();
		this.mtlrender$pendingItemSnapshot = null;
		long lifecycleGeneration = GuiRenderSnapshot.lifecycleGeneration();
		if (this.mtlrender$lifecycleGeneration != lifecycleGeneration) {
			this.mtlrender$closeReplayLease();
			if (this.mtlrender$lifecycleGeneration >= 0L) {
				GuiFrameCacheStats.recordInvalidation(GuiFrameCacheStats.InvalidationReason.LIFECYCLE);
			}
			this.mtlrender$preparedFrames.invalidate();
			this.mtlrender$lifecycleGeneration = lifecycleGeneration;
		}
		GuiFrameCacheKey key = GuiRenderSnapshot.current();
		this.mtlrender$preparedFrames.recordExtraction();
		boolean enabled = Config.get().gui.frameCache;
		if (AbSwitch.is(AbSwitch.GUI_FRAME, enabled) && key != null) {
			GuiFrameCache.Lease<GuiPreparedFrame> lease = this.mtlrender$preparedFrames.lookup(key);
			if (lease != null) {
				this.mtlrender$replayLease = lease;
				this.mtlrender$replayFrame = lease.replayableValue();
				this.mtlrender$preparedFrames.recordReplay();
			}
		}
		if (this.mtlrender$replayFrame == null && GuiGeometryExecutor.enabled()) {
			GuiFrameCacheStats.recordEncoding();
			this.mtlrender$guiFrame = this.mtlrender$guiGeometryExecutor.beginFrame();
		}
	}

	@Inject(method = "addElementsToMeshes", at = @At("HEAD"))
	private void mtlrender$submitGuiGeometry(final GuiRenderState.TraverseRange range, final CallbackInfo callbackInfo) {
		if (range == GuiRenderState.TraverseRange.AFTER_BLUR) {
			this.mtlrender$firstGuiElementAfterBlur = this.mtlrender$guiElementIndex;
		}
		if (this.mtlrender$guiFrame != null && this.mtlrender$guiResult == null) {
			this.renderState.forEachElement(
				state -> {
					GuiPictureGeometry.Snapshot picture = this.mtlrender$pictureSnapshots.get(state);
					if (picture != null) {
						this.mtlrender$guiFrame.submitPicture(state, picture);
					} else {
						this.mtlrender$guiFrame.submit(state, this.mtlrender$itemSnapshots.get(state));
					}
				},
				GuiRenderState.TraverseRange.ALL
			);
			this.mtlrender$guiResult = this.mtlrender$guiFrame.await();
		}
	}

	@Inject(method = "addElementsToMeshes", at = @At("TAIL"))
	private void mtlrender$finishGuiGeometry(final GuiRenderState.TraverseRange range, final CallbackInfo callbackInfo) {
		if (range == GuiRenderState.TraverseRange.AFTER_BLUR) {
			this.mtlrender$capturePreparedFrame();
			this.mtlrender$guiFrame = null;
			this.mtlrender$guiResult = null;
			this.mtlrender$itemSnapshots.clear();
			this.mtlrender$pictureSnapshots.clear();
			this.mtlrender$pendingPictureSnapshots.clear();
			this.mtlrender$pendingItemSnapshot = null;
		}
	}

	@WrapOperation(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")
	)
	private RenderTarget mtlrender$guiTarget(final GameRenderer renderer, final Operation<RenderTarget> original) {
		MetalFx.Composition composition = MetalFx.composition();
		if (composition.nativeTarget() == null) {
			return original.call(renderer);
		}
		return GuiNativeResolution.target(original.call(renderer), composition, Config.get().gui.nativeResolution);
	}

	@WrapOperation(
		method = "draw",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V")
	)
	private void mtlrender$guiProjection(
		final Projection projection,
		final float near,
		final float far,
		final float width,
		final float height,
		final boolean inverted,
		final Operation<Void> original
	) {
		WindowRenderState window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
		GuiNativeResolution.Viewport viewport = GuiNativeResolution.viewport(
			MetalFx.composition(), Config.get().gui.nativeResolution, window.width, window.height
		);
		original.call(projection, near, far, viewport.width() / (float) window.guiScale, viewport.height() / (float) window.guiScale, inverted);
	}

	@Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
	private void mtlrender$guiScissor(final ScreenRectangle rectangle, final RenderPass renderPass, final CallbackInfo callbackInfo) {
		WindowRenderState window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
		GuiNativeResolution.Viewport viewport = GuiNativeResolution.viewport(
			MetalFx.composition(), Config.get().gui.nativeResolution, window.width, window.height
		);
		GuiNativeResolution.Scissor scissor = GuiNativeResolution.scissor(rectangle, window.guiScale, viewport);
		renderPass.enableScissor(scissor.left(), scissor.bottom(), scissor.width(), scissor.height());
		callbackInfo.cancel();
	}

	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;upload()V")
	)
	private void mtlrender$awaitGuiGeometry(final CallbackInfo callbackInfo) {
		if (Config.get().gui.mapAtlas) {
			GuiMapAtlas.flushCurrent();
		}
		if (this.mtlrender$guiFrame != null && this.mtlrender$guiResult == null) {
			this.renderState.forEachElement(
				state -> {
					GuiPictureGeometry.Snapshot picture = this.mtlrender$pictureSnapshots.get(state);
					if (picture != null) {
						this.mtlrender$guiFrame.submitPicture(state, picture);
					} else {
						this.mtlrender$guiFrame.submit(state, this.mtlrender$itemSnapshots.get(state));
					}
				},
				GuiRenderState.TraverseRange.ALL
			);
			this.mtlrender$guiResult = this.mtlrender$guiFrame.await();
		}
		this.mtlrender$guiFrame = null;
	}

	@WrapOperation(
		method = "preparePictureInPictureState",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/pip/PictureInPictureRenderer;prepare(Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;I)V")
	)
	private void mtlrender$recordPictureInPictureBlit(
		final PictureInPictureRenderer<?> renderer,
		final PictureInPictureRenderState pictureState,
		final GuiRenderState guiState,
		final FeatureRenderDispatcher featureRenderDispatcher,
		final int guiScale,
		final Operation<Void> original
	) {
		if (this.mtlrender$guiFrame == null) {
			original.call(renderer, pictureState, guiState, featureRenderDispatcher, guiScale);
			return;
		}
		IdentityHashMap<GuiElementRenderState, Boolean> existing = new IdentityHashMap<>();
		this.renderState.forEachElement(state -> existing.put(state, Boolean.TRUE), GuiRenderState.TraverseRange.ALL);
		original.call(renderer, pictureState, guiState, featureRenderDispatcher, guiScale);
		PictureInPictureRendererAccess access = (PictureInPictureRendererAccess)renderer;
		GuiPictureGeometry.Snapshot snapshot = GuiPictureGeometry.snapshot(
			pictureState,
			TextureSetup.singleTexture(access.mtlrender$textureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST))
		);
		List<GuiElementRenderState> candidates = new ArrayList<>();
		this.renderState.forEachElement(state -> {
			if (!existing.containsKey(state)
				&& !this.mtlrender$pictureSnapshots.containsKey(state)
				&& state instanceof BlitRenderState blit
				&& this.mtlrender$matches(blit, snapshot)) {
				candidates.add(state);
			}
		}, GuiRenderState.TraverseRange.ALL);
		if (!candidates.isEmpty()) {
			this.mtlrender$pictureSnapshots.put(candidates.get(candidates.size() - 1), snapshot);
		}
	}

	@Inject(method = "prepareItemElements", at = @At("TAIL"))
	private void mtlrender$connectOversizedItemGeometry(final CallbackInfo callbackInfo) {
		if (this.mtlrender$guiFrame == null || this.mtlrender$pendingPictureSnapshots.isEmpty()) {
			return;
		}
		List<GuiPictureGeometry.Snapshot> remaining = new ArrayList<>(this.mtlrender$pendingPictureSnapshots);
		this.renderState.forEachElement(state -> {
			if (this.mtlrender$pictureSnapshots.containsKey(state) || !(state instanceof BlitRenderState blit)) {
				return;
			}
			for (int index = 0; index < remaining.size(); index++) {
				GuiPictureGeometry.Snapshot snapshot = remaining.get(index);
				if (this.mtlrender$matches(blit, snapshot)) {
					this.mtlrender$pictureSnapshots.put(state, snapshot);
					remaining.remove(index);
					break;
				}
			}
		}, GuiRenderState.TraverseRange.ALL);
		this.mtlrender$pendingPictureSnapshots.clear();
	}

	@WrapOperation(
		method = "prepareItemElements",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/pip/OversizedItemRenderer;prepare(Lnet/minecraft/client/renderer/state/gui/pip/OversizedItemRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;I)V")
	)
	private void mtlrender$recordOversizedItemBlit(
		final OversizedItemRenderer renderer,
		final OversizedItemRenderState itemState,
		final GuiRenderState guiState,
		final FeatureRenderDispatcher featureRenderDispatcher,
		final int guiScale,
		final Operation<Void> original
	) {
		original.call(renderer, itemState, guiState, featureRenderDispatcher, guiScale);
		if (this.mtlrender$guiFrame != null) {
			PictureInPictureRendererAccess access = (PictureInPictureRendererAccess)renderer;
			this.mtlrender$pendingPictureSnapshots.add(
				GuiPictureGeometry.snapshot(
					itemState,
					TextureSetup.singleTexture(access.mtlrender$textureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST))
				)
			);
		}
	}

	@Unique
	private void mtlrender$capturePreparedFrame() {
		GuiGeometryExecutor.OrderedResult result = this.mtlrender$guiResult;
		GuiFrameCacheKey key = GuiRenderSnapshot.current();
		if (result != null && result.fallbackIndexes().isEmpty() && key != null && this.mtlrender$firstGuiElementAfterBlur >= 0) {
			GuiPreparedFrame frame = GuiPreparedFrame.capture(
				key,
				result.orderedBatches(),
				this.mtlrender$firstGuiElementAfterBlur
			);
			this.mtlrender$preparedFrames.recordPreparation();
			this.mtlrender$preparedFrames.publish(key, frame);
		}
	}

	@Unique
	private boolean mtlrender$matches(final BlitRenderState blit, final GuiPictureGeometry.Snapshot snapshot) {
		return blit.pipeline() == snapshot.pipeline()
			&& blit.textureSetup().equals(snapshot.textureSetup())
			&& blit.pose().equals(snapshot.pose())
			&& blit.x0() == snapshot.x0()
			&& blit.y0() == snapshot.y0()
			&& blit.x1() == snapshot.x1()
			&& blit.y1() == snapshot.y1()
			&& blit.u0() == snapshot.u0()
			&& blit.v0() == snapshot.v0()
			&& blit.u1() == snapshot.u1()
			&& blit.v1() == snapshot.v1()
			&& blit.color() == snapshot.color()
			&& Objects.equals(blit.scissorArea(), snapshot.scissorArea());
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mtlrender$closeGuiGeometryExecutor(final CallbackInfo callbackInfo) {
		GuiFrameCacheStats.recordInvalidation(GuiFrameCacheStats.InvalidationReason.CLOSE);
		this.mtlrender$closeReplayLease();
		this.mtlrender$preparedFrames.invalidate();
		this.mtlrender$guiGeometryExecutor.close();
		GuiMapAtlas.invalidate();
	}

	@Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"))
	private void mtlrender$prepareItemGeometry(
		final GuiItemRenderState itemState,
		final GuiItemAtlas.SlotView slotView,
		final CallbackInfo callbackInfo
	) {
		if (this.mtlrender$guiFrame != null) {
			this.mtlrender$pendingItemSnapshot = GuiItemGeometry.snapshot(
				itemState,
				slotView,
				RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)
			);
		}
	}

	@WrapOperation(
		method = "submitBlitFromItemAtlas",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addBlitToCurrentLayer(Lnet/minecraft/client/renderer/state/gui/BlitRenderState;)V")
	)
	private void mtlrender$recordItemGeometry(
		final GuiRenderState state,
		final BlitRenderState blit,
		final Operation<Void> original
	) {
		original.call(state, blit);
		GuiItemGeometry.Snapshot snapshot = this.mtlrender$pendingItemSnapshot;
		if (snapshot != null) {
			this.mtlrender$itemSnapshots.put(blit, snapshot);
			this.mtlrender$pendingItemSnapshot = null;
		}
	}

	@WrapOperation(
		method = "addElementToMesh",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiElementRenderState;buildVertices(Lcom/mojang/blaze3d/vertex/VertexConsumer;)V")
	)
	private void mtlrender$replayGuiGeometry(
		GuiElementRenderState state,
		VertexConsumer consumer,
		Operation<Void> original
	) {
		GuiGeometryExecutor.OrderedResult result = this.mtlrender$guiResult;
		GuiGeometryBatch batch = result == null && this.mtlrender$replayFrame != null
			? this.mtlrender$replayFrame.batchAt(this.mtlrender$guiElementIndex++)
			: result == null ? null : result.batchAt(this.mtlrender$guiElementIndex++);
		if (batch == null && result == null && this.mtlrender$replayFrame == null) {
			original.call(state, consumer);
			return;
		}
		if (batch == null) {
			original.call(state, consumer);
		} else {
			batch.replay(consumer);
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void mtlrender$releaseReplayAfterSubmission(final CallbackInfo callbackInfo) {
		this.mtlrender$deferReplayLease();
	}

	@Unique
	private void mtlrender$deferReplayLease() {
		GuiFrameCache.Lease<GuiPreparedFrame> lease = this.mtlrender$replayLease;
		this.mtlrender$replayLease = null;
		this.mtlrender$replayFrame = null;
		if (lease == null) {
			return;
		}
		if (!MetalAccess.addCompletion(lease::close)) {
			lease.close();
		}
	}

	@Unique
	private void mtlrender$closeReplayLease() {
		GuiFrameCache.Lease<GuiPreparedFrame> lease = this.mtlrender$replayLease;
		this.mtlrender$replayLease = null;
		this.mtlrender$replayFrame = null;
		if (lease != null) {
			lease.close();
		}
	}

	@WrapOperation(
		method = "prepare",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;sortElements(Ljava/util/Comparator;)V")
	)
	private void mtlrender$batchGuiElements(
		GuiRenderState state,
		Comparator<GuiElementRenderState> comparator,
		Operation<Void> original
	) {
		boolean enabled = Config.get().gui.batching;
		if ("guiBatch".equals(AbSwitch.NAME)) {
			enabled = AbSwitch.b();
		}
		if (enabled) {
			original.call(state, comparator);
		}
	}
}
