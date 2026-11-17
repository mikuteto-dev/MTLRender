package dev.mikuteto.mtlrender.pack.shadow;

import dev.mikuteto.mtlrender.pack.gbuffer.GbufferDrawBridge;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferDrawState;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSemanticContext;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferSubmitMetadata;

import java.util.Objects;
import java.util.Optional;

public final class ShadowFeatureBridge {
	public enum FeatureKind {
		NONE,
		ENTITY,
		BLOCK_ENTITY
	}

	public record State(ShadowPassPlan plan, ShadowProgramSelection entityProgram,
		ShadowProgramSelection blockEntityProgram, FeatureKind featureKind, int entityId, int blockEntityId,
		Optional<GbufferDrawState> draw) {
		public State {
			Objects.requireNonNull(plan, "shadow plan");
			Objects.requireNonNull(entityProgram, "entity program");
			Objects.requireNonNull(blockEntityProgram, "block entity program");
			Objects.requireNonNull(featureKind, "feature kind");
			draw = Objects.requireNonNull(draw, "draw");
			if (entityId < 0 || blockEntityId < 0) throw new IllegalArgumentException("shadow IDs must not be negative");
		}

		public boolean active() {
			return plan.shadowEntities() || plan.shadowBlockEntities();
		}

		public boolean submitsEntity() {
			return plan.shadowEntities() && entityProgram.resolved() && featureKind == FeatureKind.ENTITY;
		}

		public boolean submitsBlockEntity() {
			return plan.shadowBlockEntities() && blockEntityProgram.resolved() && featureKind == FeatureKind.BLOCK_ENTITY;
		}

		public ShadowMatrices matrices() {
			return plan.matrices();
		}
	}

	private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

	private ShadowFeatureBridge() {
	}

	public static Scope enter(ShadowPassPlan plan, ShadowProgramResolver resolver) {
		Objects.requireNonNull(resolver, "shadow resolver");
		return enter(plan, resolver.resolve(ShadowProgramKind.ENTITIES, true),
			resolver.resolve(ShadowProgramKind.BLOCK_ENTITIES, true));
	}

	public static Scope enter(ShadowPassPlan plan, ShadowProgramSelection entity,
		ShadowProgramSelection blockEntity) {
		Objects.requireNonNull(plan, "shadow plan");
		Objects.requireNonNull(entity, "entity program");
		Objects.requireNonNull(blockEntity, "block entity program");
		if (!plan.shadowEntities() && !plan.shadowBlockEntities()) return Scope.inactive();
		if ((!plan.shadowEntities() || !entity.resolved()) && (!plan.shadowBlockEntities() || !blockEntity.resolved())) {
			return Scope.inactive();
		}
		return install(new State(plan, entity, blockEntity, FeatureKind.NONE, ids().entityId(), ids().blockEntityId(), ids().draw()));
	}

	public static Scope enterFeature(FeatureKind kind) {
		Objects.requireNonNull(kind, "feature kind");
		State previous = CURRENT.get();
		if (previous == null) return Scope.inactive();
		Ids ids = ids();
		return install(new State(previous.plan(), previous.entityProgram(), previous.blockEntityProgram(), kind,
			ids.entityId(), ids.blockEntityId(), ids.draw()));
	}

	public static boolean active() {
		return CURRENT.get() != null;
	}

	public static Optional<State> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	public static boolean shouldSubmitEntity() {
		State state = CURRENT.get();
		return state != null && state.submitsEntity();
	}

	public static boolean shouldSubmitBlockEntity() {
		State state = CURRENT.get();
		return state != null && state.submitsBlockEntity();
	}

	public static void clear() {
		CURRENT.remove();
	}

	private static Scope install(State next) {
		State previous = CURRENT.get();
		CURRENT.set(next);
		return new Scope(previous, true);
	}

	private static Ids ids() {
		Optional<GbufferDrawBridge.Draw> draw = GbufferDrawBridge.current();
		if (draw.isPresent()) {
			GbufferDrawState state = draw.orElseThrow().state();
			return new Ids(state.entityId(), state.blockEntityId(), Optional.of(state));
		}
		GbufferSubmitMetadata metadata = GbufferSemanticContext.currentMetadata();
		return new Ids(metadata.entityId(), metadata.blockEntityId(), Optional.empty());
	}

	private record Ids(int entityId, int blockEntityId, Optional<GbufferDrawState> draw) {
	}

	public static final class Scope implements AutoCloseable {
		private final State previous;
		private final boolean active;
		private boolean closed;

		private Scope(State previous, boolean active) {
			this.previous = previous;
			this.active = active;
		}

		private static Scope inactive() {
			return new Scope(null, false);
		}

		@Override
		public void close() {
			if (closed) return;
			if (active) {
				if (CURRENT.get() == null) throw new IllegalStateException("shadow feature scope is not active");
				if (previous == null) CURRENT.remove();
				else CURRENT.set(previous);
			}
			closed = true;
		}
	}
}
