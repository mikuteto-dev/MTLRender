package dev.mikuteto.mtlrender.pack.gbuffer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class GbufferSemanticContext {
	private static final ThreadLocal<Frame> FRAMES = new ThreadLocal<>();

	private GbufferSemanticContext() {
	}

	public static void beginFrame() {
		FRAMES.set(new Frame());
	}

	public static void endFrame() {
		FRAMES.remove();
	}

	public static void clear() {
		FRAMES.remove();
	}

	public static boolean isActive() {
		return FRAMES.get() != null;
	}

	public static boolean active() {
		return isActive();
	}

	public static GbufferDrawState current() {
		Frame frame = FRAMES.get();
		return frame == null ? GbufferDrawState.zero() : frame.state;
	}

	public static GbufferSubmitMetadata currentMetadata() {
		Frame frame = FRAMES.get();
		return frame == null ? GbufferSubmitMetadata.stage(GbufferStage.Sky) : frame.metadataState;
	}

	public static Optional<GbufferFeatureStage> featureStage() {
		Frame frame = FRAMES.get();
		return frame == null ? Optional.empty() : Optional.ofNullable(frame.featureStage);
	}

	public static void recordState(Object state, GbufferSubmitMetadata metadata) {
		recordSubmit(state, metadata);
	}

	public static void recordAssociation(Object value, GbufferSubmitMetadata metadata) {
		if (value != null) recordSubmit(value, metadata);
	}

	public static Scope enterFeature(GbufferFeatureStage stage) {
		Objects.requireNonNull(stage, "stage");
		Frame frame = FRAMES.get();
		if (frame == null) return Scope.inactive();
		return enterScope(frame, frame.state, frame.metadataState, frame.featureStage, stage);
	}

	public static Scope enter(GbufferSubmitMetadata metadata) {
		Objects.requireNonNull(metadata, "metadata");
		Frame frame = requireFrame();
		GbufferDrawState previous = frame.state;
		GbufferSubmitMetadata previousMetadata = frame.metadataState;
		frame.state = state(metadata);
		frame.metadataState = metadata;
		return enterScope(frame, previous, previousMetadata, frame.featureStage, frame.featureStage);
	}

	public static Scope enter(GbufferStage stage) {
		return enter(GbufferSubmitMetadata.stage(stage));
	}

	public static Scope enterWorld(GbufferStage stage) {
		Objects.requireNonNull(stage, "stage");
		Frame frame = FRAMES.get();
		return frame == null ? Scope.noop() : enter(GbufferSubmitMetadata.stage(stage));
	}

	public static Scope enterStage(GbufferStage stage) {
		Objects.requireNonNull(stage, "stage");
		if (!isActive()) return enterFeature(GbufferFeatureStage.Solid);
		GbufferSubmitMetadata previous = currentMetadata();
		return enter(new GbufferSubmitMetadata(previous.entityId(), previous.blockEntityId(),
			previous.currentRenderedItemId(), stage));
	}

	public static Scope enterItem(int itemId) {
		Frame frame = FRAMES.get();
		if (frame == null) return Scope.noop();
		GbufferDrawState previous = frame.state;
		GbufferSubmitMetadata previousMetadata = frame.metadataState;
		GbufferDrawState current = new GbufferDrawState(previous.entityId(), previous.blockEntityId(), previous.renderStage(),
			itemId < 0 ? 0 : itemId, previous.mcEntity(), previous.midBlock(), previous.tangent());
		frame.state = current;
		return new Scope(frame, previous, previousMetadata, frame.featureStage);
	}

	public static void recordSubmit(Object submit, GbufferSubmitMetadata metadata) {
		Objects.requireNonNull(submit, "submit");
		Objects.requireNonNull(metadata, "metadata");
		Frame frame = requireFrame();
		frame.metadata.put(submit, metadata);
	}

	public static Optional<GbufferSubmitMetadata> metadata(Object submit) {
		if (submit == null) return Optional.empty();
		Frame frame = FRAMES.get();
		return frame == null ? Optional.empty() : Optional.ofNullable(frame.metadata.get(submit));
	}

	public static final class Scope implements AutoCloseable {
		private final Frame frame;
		private final GbufferDrawState previous;
		private final GbufferSubmitMetadata previousMetadata;
		private final GbufferFeatureStage previousFeatureStage;
		private boolean closed;

		private Scope(Frame frame, GbufferDrawState previous, GbufferSubmitMetadata previousMetadata,
				GbufferFeatureStage previousFeatureStage) {
			this.frame = frame;
			this.previous = previous;
			this.previousMetadata = previousMetadata;
			this.previousFeatureStage = previousFeatureStage;
		}

		private static Scope inactive() {
			return new Scope(null, null, null, null);
		}

		private static Scope noop() {
			return new Scope(null, null, null, null);
		}

		@Override
		public void close() {
			if (closed) return;
			if (frame == null) {
				closed = true;
				return;
			}
			if (FRAMES.get() != frame) throw new IllegalStateException("gbuffer scope is not in its frame");
			frame.state = previous;
			frame.metadataState = previousMetadata;
			frame.featureStage = previousFeatureStage;
			closed = true;
		}
	}

	private static Scope enterScope(Frame frame, GbufferDrawState previous, GbufferSubmitMetadata previousMetadata,
			GbufferFeatureStage previousFeatureStage, GbufferFeatureStage currentFeatureStage) {
		frame.featureStage = currentFeatureStage;
		return new Scope(frame, previous, previousMetadata, previousFeatureStage);
	}

	private static Frame requireFrame() {
		Frame frame = FRAMES.get();
		if (frame == null) throw new IllegalStateException("gbuffer frame is not active");
		return frame;
	}

	private static GbufferDrawState state(GbufferSubmitMetadata metadata) {
		return new GbufferDrawState(metadata.entityId(), metadata.blockEntityId(), metadata.stage().ordinal(),
			metadata.currentRenderedItemId(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
	}

	private static final class Frame {
		private GbufferDrawState state = GbufferDrawState.zero();
		private GbufferSubmitMetadata metadataState = GbufferSubmitMetadata.stage(GbufferStage.Sky);
		private GbufferFeatureStage featureStage;
		private final Map<Object, GbufferSubmitMetadata> metadata = new IdentityHashMap<>();
	}
}
