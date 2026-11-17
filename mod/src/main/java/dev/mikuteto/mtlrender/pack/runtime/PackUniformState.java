package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.uniform.GameState;
import dev.mikuteto.mtlrender.pack.shadow.ShadowFrameSnapshot;
import dev.mikuteto.mtlrender.pack.uniform.Type;
import dev.mikuteto.mtlrender.pack.uniform.UniformEngine;
import dev.mikuteto.mtlrender.pack.uniform.UniformValues;
import dev.mikuteto.mtlrender.pack.uniform.Value;
import dev.mikuteto.mtlrender.pack.uniform.Values;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PackUniformState {
	private final GameState gameState;
	private final UniformEngine engine;
	private final GameState passValues;
	private final Values values;
	private long frame;
	private boolean collected;

	public PackUniformState(ShaderProperties properties) {
		Objects.requireNonNull(properties, "properties");
		gameState = new GameState();
		engine = new UniformEngine(gameState, definitions(properties));
		passValues = new GameState();
		values = new OverlayValues(passValues, engine);
	}

	public static PackUniformState from(ShaderPack pack) {
		return new PackUniformState(Objects.requireNonNull(pack, "pack").properties());
	}

	public void collect(Minecraft client, Camera camera, LevelRenderState renderState, DeltaTracker deltaTracker) {
		Objects.requireNonNull(client, "client");
		gameState.collect(client, camera, renderState, deltaTracker);
		double seconds = deltaTracker == null ? 0.0 : deltaTracker.getRealtimeDeltaTicks() / 20.0;
		engine.update(seconds);
		frame++;
		collected = true;
	}

	public void collect(Minecraft client, Camera camera, LevelRenderState renderState, DeltaTracker deltaTracker,
		ShadowFrameSnapshot shadowFrame) {
		collect(client, camera, renderState, deltaTracker);
		gameState.publishShadow(Objects.requireNonNull(shadowFrame, "shadow frame"));
	}

	public long frame() {
		return frame;
	}

	List<String> customOrder() {
		return engine.order();
	}

	public GameState gameState() {
		return gameState;
	}

	void updateCustom(double deltaSeconds) {
		engine.update(deltaSeconds);
	}

	public boolean collected() {
		return collected;
	}

	public Values values() {
		return values;
	}

	public Values baseValues() {
		return engine;
	}

	public ByteBuffer write(PackUniformBindings bindings) {
		return Objects.requireNonNull(bindings, "bindings").write(values);
	}

	public GpuBufferSlice write(PackUniformBindings bindings, TransientMemory memory) {
		Objects.requireNonNull(bindings, "bindings");
		return bindings.write(Objects.requireNonNull(memory, "memory"), values);
	}

	public Values forPass(RuntimeFrame frame, PassKind pass) {
		Objects.requireNonNull(frame, "frame");
		Objects.requireNonNull(pass, "pass");
		int width = Math.max(1, Math.round(frame.viewport().width() * frame.scale().x()));
		int height = Math.max(1, Math.round(frame.viewport().height() * frame.scale().y()));
		setPass("renderStage", Type.INT, pass.order());
		setPass("viewWidth", Type.FLOAT, width);
		setPass("viewHeight", Type.FLOAT, height);
		setPass("iris_ScreenSize", Type.VEC2, width, height);
		setPass("aspectRatio", Type.FLOAT, (double) width / height);
		return values;
	}

	public void setPassValue(String name, Type type, double... components) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(type, "type");
		Value value = passValues.require(name, type);
		value.zero();
		for (int index = 0; index < components.length && index < type.components(); index++) {
			value.setNumber(index, components[index]);
		}
	}

	public void setPassValue(String name, Type type, int... components) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(type, "type");
		Value value = passValues.require(name, type);
		value.zero();
		for (int index = 0; index < components.length && index < type.components(); index++) {
			value.setInteger(index, components[index]);
		}
	}

	public void setPassValue(String name, boolean value) {
		passValues.set(name, value);
	}

	private void setPass(String name, Type type, double... components) {
		setPassValue(name, type, components);
	}

	private static List<UniformEngine.Definition> definitions(ShaderProperties properties) {
		List<UniformEngine.Definition> result = new ArrayList<>();
		addDefinitions(result, properties.uniforms(), true);
		addDefinitions(result, properties.variables(), false);
		return List.copyOf(result);
	}

	private static void addDefinitions(List<UniformEngine.Definition> result,
		Map<String, Map<String, String>> groups, boolean uniform) {
		for (Map.Entry<String, Map<String, String>> group : groups.entrySet()) {
			Type type = Type.parse(group.getKey());
			for (Map.Entry<String, String> definition : group.getValue().entrySet()) {
				result.add(new UniformEngine.Definition(definition.getKey(), type, definition.getValue(), uniform));
			}
		}
	}

	private static final class OverlayValues implements Values {
		private final UniformValues overlay;
		private final Values base;

		private OverlayValues(UniformValues overlay, Values base) {
			this.overlay = overlay;
			this.base = base;
		}

		@Override
		public Value value(String name) {
			Value value = overlay.value(name);
			return value == null ? base.value(name) : value;
		}
	}
}
