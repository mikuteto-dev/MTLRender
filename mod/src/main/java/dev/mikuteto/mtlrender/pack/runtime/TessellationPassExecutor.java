package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.tess.TessellationBuffers;
import dev.mikuteto.mtlrender.pack.tess.TessellationDescriptor;
import dev.mikuteto.mtlrender.pack.tess.TessellationDispatcher;

import java.util.Objects;
import java.util.Optional;

public final class TessellationPassExecutor {
	public interface Recorder extends TessellationDispatcher.Commands, TessellationDispatcher.Draw {
		void barrier();
		void beginRender(PackPassPlan plan, TessellationDescriptor descriptor);
		void bindResources(PackPassPlan plan, GpuBufferSlice uniformSlice);
		void endRender();
	}

	public record Prepared(
		PackProgramRequest request,
		PackPassPlan pass,
		TessellationDescriptor descriptor,
		long controlPipeline,
		long tessellatorPipeline,
		int controlGroupsX,
		int controlGroupsY,
		int controlGroupsZ,
		TessellationBuffers buffers,
		int vertexCount,
		int indexCount,
		byte[] parameters,
		GpuBufferSlice uniformSlice
	) {
		public Prepared {
			Objects.requireNonNull(request, "request");
			Objects.requireNonNull(pass, "pass");
			Objects.requireNonNull(descriptor, "descriptor");
			if (controlPipeline <= 0 || tessellatorPipeline <= 0) throw new IllegalArgumentException("tessellation pipelines must be positive");
			if (controlGroupsX <= 0 || controlGroupsY <= 0 || controlGroupsZ <= 0) throw new IllegalArgumentException("control dispatch dimensions must be positive");
			Objects.requireNonNull(buffers, "buffers");
			if (vertexCount <= 0 || indexCount <= 0) throw new IllegalArgumentException("tessellation draw counts must be positive");
			parameters = Objects.requireNonNull(parameters, "parameters").clone();
			Objects.requireNonNull(uniformSlice, "uniform slice");
			if (!request.source(Program.Stage.TESS_CONTROL).isPresent()
				|| !request.source(Program.Stage.TESS_EVALUATION).isPresent()) {
				throw new IllegalArgumentException("tessellation pass requires control and evaluation sources");
			}
		}
	}

	public record Result(boolean recorded, Optional<PackProgramFailure> failure) {
		public Result {
			Objects.requireNonNull(failure, "failure");
			if (recorded && failure.isPresent()) throw new IllegalArgumentException("recorded execution cannot fail");
		}
		public static Result empty() { return new Result(false, Optional.empty()); }
		public static Result success() { return new Result(true, Optional.empty()); }
	}

	private final Recorder recorder;

	public TessellationPassExecutor(Recorder recorder) {
		this.recorder = Objects.requireNonNull(recorder, "recorder");
	}

	public Result execute(Optional<Prepared> prepared) {
		Objects.requireNonNull(prepared, "prepared");
		return prepared.map(this::execute).orElseGet(Result::empty);
	}

	public Result execute(Prepared prepared) {
		Objects.requireNonNull(prepared, "prepared");
		try {
			TessellationDispatcher dispatcher = new TessellationDispatcher(recorder,
				new TessellationDispatcher.Kernels(prepared.controlPipeline(), prepared.tessellatorPipeline(),
					prepared.controlGroupsX(), prepared.controlGroupsY(), prepared.controlGroupsZ()));
			TessellationDispatcher.Result result = dispatcher.dispatch(prepared.buffers(), prepared.vertexCount(), prepared.indexCount(), prepared.parameters());
			recorder.barrier();
			recorder.beginRender(prepared.pass(), prepared.descriptor());
			boolean closed = false;
			try {
				recorder.bindResources(prepared.pass(), prepared.uniformSlice());
				dispatcher.draw(result, recorder);
				recorder.endRender();
				closed = true;
			} finally {
				if (!closed) recorder.endRender();
			}
			return Result.success();
		} catch (RuntimeException failure) {
			return failed(prepared, failure);
		}
	}

	private static Result failed(Prepared prepared, RuntimeException failure) {
		Program.Stage stage = prepared.request().source(Program.Stage.TESS_CONTROL).isPresent()
			? Program.Stage.TESS_CONTROL : Program.Stage.TESS_EVALUATION;
		String message = failure.getMessage();
		if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
		return new Result(false, Optional.of(new PackProgramFailure(prepared.request().pack(), prepared.request().dimension(), prepared.request().program(), stage, message)));
	}
}
