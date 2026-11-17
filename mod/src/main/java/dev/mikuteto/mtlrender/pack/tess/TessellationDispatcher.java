package dev.mikuteto.mtlrender.pack.tess;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.mikuteto.mtlrender.backend.MetalAccess;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class TessellationDispatcher {
	public interface Commands {
		void begin(boolean allowConcurrent);
		void pipeline(long handle);
		void buffer(int slot, GpuBuffer buffer, long offset, MetalAccess.Access access);
		void bytes(int slot, byte[] data);
		void dispatch(int x, int y, int z);
		void end();
	}

	public interface Draw {
		void vertex(GpuBufferSlice slice);
		void index(GpuBuffer buffer);
		void indexed(int count, int instances, int firstIndex, int baseVertex, int firstInstance);
	}

	public record Kernels(long control, long tessellator, int controlGroupsX, int controlGroupsY, int controlGroupsZ) {
		public Kernels {
			if (control == 0 || tessellator == 0) throw new IllegalArgumentException("tessellation pipeline handles must be non-zero");
			if (controlGroupsX <= 0 || controlGroupsY <= 0 || controlGroupsZ <= 0) throw new IllegalArgumentException("control dispatch dimensions must be positive");
		}
	}

	public record Result(GpuBufferSlice vertices, GpuBufferSlice indices, int indexCount) {
		public Result {
			Objects.requireNonNull(vertices, "vertices");
			Objects.requireNonNull(indices, "indices");
			if (indexCount <= 0) throw new IllegalArgumentException("index count must be positive");
		}
	}

	private final Commands commands;
	private final Kernels kernels;

	public TessellationDispatcher(Commands commands, Kernels kernels) {
		this.commands = Objects.requireNonNull(commands, "commands");
		this.kernels = Objects.requireNonNull(kernels, "kernels");
	}

	public static Commands metalCommands() {
		return new Commands() {
			@Override public void begin(boolean allowConcurrent) { MetalAccess.beginCompute(allowConcurrent); }
			@Override public void pipeline(long handle) { MetalAccess.usePackComputePipeline(handle); }
			@Override public void buffer(int slot, GpuBuffer buffer, long offset, MetalAccess.Access access) {
				MetalAccess.bindTessellationBuffer(slot, buffer, offset, access);
			}
			@Override public void bytes(int slot, byte[] data) { MetalAccess.setPackComputeBytes(slot, ByteBuffer.wrap(data)); }
			@Override public void dispatch(int x, int y, int z) { MetalAccess.dispatchTessellationCompute(x, y, z); }
			@Override public void end() { MetalAccess.endCompute(); }
		};
	}

	public Result dispatch(TessellationBuffers buffers, int indexCount, byte[] parameters) {
		return dispatch(buffers, Math.toIntExact(buffers.coordinates().size() / TessellationBuffers.COORDINATE_STRIDE), indexCount, parameters);
	}

	public Result dispatch(TessellationBuffers buffers, int vertexCount, int indexCount, byte[] parameters, float[] factors) {
		TessellationBuffers.validateFactors(factors);
		return dispatch(buffers, vertexCount, indexCount, parameters);
	}

	public Result dispatch(TessellationBuffers buffers, int vertexCount, int indexCount, byte[] parameters) {
		Objects.requireNonNull(buffers, "buffers");
		Objects.requireNonNull(parameters, "parameters");
		if (vertexCount <= 0 || (long) vertexCount * TessellationBuffers.COORDINATE_STRIDE > buffers.coordinates().size()) {
			throw new IllegalArgumentException("tessellation vertex output exceeds its allocation");
		}
		if (indexCount <= 0 || (long) indexCount * TessellationBuffers.INDEX_STRIDE > buffers.indices().size()) {
			throw new IllegalArgumentException("tessellation index output exceeds its allocation");
		}
		if ((long) buffers.patchCount() * TessellationBuffers.FACTOR_STRIDE > buffers.factorControl().size()) {
			throw new IllegalArgumentException("tessellation factor output exceeds its allocation");
		}
		commands.begin(false);
		boolean ended = false;
		try {
			commands.pipeline(kernels.control());
			commands.buffer(0, buffers.patchInput(), 0, MetalAccess.Access.READ);
			commands.buffer(1, buffers.factorControl(), 0, MetalAccess.Access.WRITE);
			commands.bytes(0, parameters);
			commands.dispatch(kernels.controlGroupsX(), kernels.controlGroupsY(), kernels.controlGroupsZ());
			commands.pipeline(kernels.tessellator());
			commands.buffer(0, buffers.factorControl(), 0, MetalAccess.Access.READ);
			commands.buffer(1, buffers.coordinates(), 0, MetalAccess.Access.WRITE);
			commands.buffer(2, buffers.indices(), 0, MetalAccess.Access.WRITE);
			commands.buffer(3, buffers.indirect(), 0, MetalAccess.Access.WRITE);
			commands.buffer(4, buffers.indirect(), buffers.offsetsOffset(), MetalAccess.Access.READ);
			commands.bytes(5, parameters);
			commands.dispatch(Math.toIntExact(buffers.patchCount()), 1, 1);
			commands.end();
			ended = true;
		} finally {
			if (!ended) commands.end();
		}
		return new Result(
			buffers.coordinateSlice(0, (long) vertexCount * TessellationBuffers.COORDINATE_STRIDE),
			buffers.indexSlice(0, (long) indexCount * TessellationBuffers.INDEX_STRIDE), indexCount);
	}

	public void draw(Result result, Draw draw) {
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(draw, "draw");
		draw.vertex(result.vertices());
		draw.index(result.indices().buffer());
		draw.indexed(result.indexCount(), 1, 0, 0, 0);
	}
}
