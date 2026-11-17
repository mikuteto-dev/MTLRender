package dev.mikuteto.mtlrender.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import dev.mikuteto.mtlrender.mixin.ItemStackRenderStateAccess;

public record GuiItemCacheKey(
	List<ModelIdentityElement> modelIdentity,
	boolean animated,
	long animationVersion,
	long resourceGeneration
) {
	public GuiItemCacheKey {
		modelIdentity = List.copyOf(Objects.requireNonNull(modelIdentity, "modelIdentity"));
		if (animationVersion < 0L) {
			throw new IllegalArgumentException("animationVersion must not be negative");
		}
		if (resourceGeneration < 0L) {
			throw new IllegalArgumentException("resourceGeneration must not be negative");
		}
	}

	public static GuiItemCacheKey of(final List<?> modelIdentity, final boolean animated, final long animationVersion, final long resourceGeneration) {
		Objects.requireNonNull(modelIdentity, "modelIdentity");
		List<ModelIdentityElement> stable = new ArrayList<>(modelIdentity.size());
		for (Object element : modelIdentity) {
			stable.add(ModelIdentityElement.of(element));
		}
		return new GuiItemCacheKey(stable, animated, animated ? animationVersion : 0L, resourceGeneration);
	}

	public static GuiItemCacheKey from(final GuiItemRenderState state, final long resourceGeneration, final long animationVersion) {
		Objects.requireNonNull(state, "state");
		return from(state.itemStackRenderState(), resourceGeneration, animationVersion);
	}

	public static GuiItemCacheKey from(final TrackingItemStackRenderState renderState, final long resourceGeneration, final long animationVersion) {
		Objects.requireNonNull(renderState, "renderState");
		Object identity = renderState.getModelIdentity();
		if (!(identity instanceof List<?> elements)) {
			throw new IllegalStateException("TrackingItemStackRenderState model identity is not a list");
		}
		boolean animated = ((ItemStackRenderStateAccess)renderState).mtlrender$isAnimated();
		return of(elements, animated, animationVersion, resourceGeneration);
	}

	public record ModelIdentityElement(Class<?> type, Object value, boolean valueStable) {
		public ModelIdentityElement {
			type = Objects.requireNonNull(type, "type");
			if (valueStable) {
				Objects.requireNonNull(value, "value");
			}
		}

		static ModelIdentityElement of(final Object value) {
			Objects.requireNonNull(value, "model identity element");
			if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>) {
				return new ModelIdentityElement(value.getClass(), value, true);
			}
			return new ModelIdentityElement(value.getClass(), new IdentityValue(value), false);
		}

		@Override
		public boolean equals(final Object other) {
			if (!(other instanceof ModelIdentityElement element) || !this.type.equals(element.type) || this.valueStable != element.valueStable) {
				return false;
			}
			return this.valueStable ? Objects.equals(this.value, element.value) : this.value.equals(element.value);
		}

		@Override
		public int hashCode() {
			return 31 * this.type.hashCode() + (this.valueStable ? Objects.hashCode(this.value) : this.value.hashCode());
		}
	}

	private static final class IdentityValue {
		private final Object value;

		private IdentityValue(final Object value) {
			this.value = value;
		}

		@Override
		public boolean equals(final Object other) {
			return other instanceof IdentityValue identity && this.value == identity.value;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.value);
		}
	}
}
