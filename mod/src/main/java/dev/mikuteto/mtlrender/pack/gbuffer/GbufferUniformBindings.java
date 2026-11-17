package dev.mikuteto.mtlrender.pack.gbuffer;

import dev.mikuteto.mtlrender.pack.runtime.PackUniformState;
import dev.mikuteto.mtlrender.pack.uniform.GameState;
import dev.mikuteto.mtlrender.pack.uniform.Type;
import dev.mikuteto.mtlrender.pack.uniform.UniformWriter;
import dev.mikuteto.mtlrender.pack.uniform.Value;
import dev.mikuteto.mtlrender.pack.uniform.Values;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GbufferUniformBindings {
	private final GbufferPipelineSpec.InputProfile profile;
	private final List<UniformWriter.Member> members;
	private final UniformWriter writer;

	public GbufferUniformBindings(GbufferPipelineSpec.InputProfile profile, List<UniformWriter.Member> members) {
		this.profile = Objects.requireNonNull(profile, "profile");
		Objects.requireNonNull(members, "members");
		this.members = List.copyOf(new ArrayList<>(members));
		this.writer = new UniformWriter();
	}

	public GbufferPipelineSpec.InputProfile profile() {
		return profile;
	}

	public List<UniformWriter.Member> members() {
		return members;
	}

	public void write(ByteBuffer buffer, GbufferDrawState draw, GameState gameState) {
		Objects.requireNonNull(buffer, "buffer");
		Objects.requireNonNull(draw, "draw");
		Objects.requireNonNull(gameState, "gameState");
		setDrawValues(draw, gameState);
		writer.write(buffer, members, new DrawValues(gameState, draw, profile));
	}

	public void write(ByteBuffer buffer, GbufferDrawState draw, PackUniformState uniforms) {
		Objects.requireNonNull(uniforms, "uniforms");
		write(buffer, draw, uniforms.gameState(), uniforms.baseValues());
	}

	private void write(ByteBuffer buffer, GbufferDrawState draw, GameState gameState, Values values) {
		setDrawValues(draw, gameState);
		writer.write(buffer, members, new DrawValues(values, draw, profile));
	}

	private static void setDrawValues(GbufferDrawState draw, GameState gameState) {
		gameState.setDrawValue("entityId", Type.INT, draw.entityId());
		gameState.setDrawValue("blockEntityId", Type.INT, draw.blockEntityId());
		gameState.setDrawValue("renderStage", Type.INT, draw.renderStage());
		gameState.setDrawValue("currentRenderedItemId", Type.INT, draw.currentRenderedItemId());
	}

	private static boolean active(String name, GbufferPipelineSpec.InputProfile profile) {
		return switch (name) {
			case "mc_Entity" -> profile == GbufferPipelineSpec.InputProfile.Block;
			case "at_midBlock" -> profile == GbufferPipelineSpec.InputProfile.Block;
			case "at_tangent" -> profile == GbufferPipelineSpec.InputProfile.Block
				|| profile == GbufferPipelineSpec.InputProfile.Entity;
			default -> true;
		};
	}

	private static final class DrawValues implements Values {
		private final Values base;
		private final GbufferDrawState draw;
		private final GbufferPipelineSpec.InputProfile profile;

		private DrawValues(Values base, GbufferDrawState draw, GbufferPipelineSpec.InputProfile profile) {
			this.base = base;
			this.draw = draw;
			this.profile = profile;
		}

		@Override
		public Value value(String name) {
			if (!active(name, profile)) {
				return null;
			}
			Optional<GbufferDrawState.Attribute> attribute = switch (name) {
				case "mc_Entity" -> draw.mcEntity();
				case "at_midBlock" -> draw.midBlock();
				case "at_tangent" -> draw.tangent();
				default -> Optional.empty();
			};
			if (attribute.isPresent()) {
				GbufferDrawState.Attribute value = attribute.get();
				Value result = new Value(Type.VEC4);
				result.setNumber(0, value.x());
				result.setNumber(1, value.y());
				result.setNumber(2, value.z());
				result.setNumber(3, value.w());
				return result;
			}
			return base.value(name);
		}
	}
}
