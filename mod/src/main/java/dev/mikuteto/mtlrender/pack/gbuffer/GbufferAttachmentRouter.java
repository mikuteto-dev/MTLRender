package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mikuteto.mtlrender.pack.runtime.PackTargetSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GbufferAttachmentRouter {
	public static final int MAX_COLOR_ATTACHMENTS = 8;

	public Route route(Request request) {
		Objects.requireNonNull(request, "request");
		if (request.pipelineIdentity() == null) return Route.noOp(Reason.NO_PIPELINE_IDENTITY);
		GbufferPipelineSpec spec = GbufferPipelineCatalog.find(request.pipeline()).orElse(null);
		if (spec == null) return Route.noOp(Reason.NOT_CATALOGED);
		if (!request.selection().active()) return Route.noOp(Reason.VANILLA);
		if (!request.packProgramValid()) return Route.noOp(Reason.PACK_PROGRAM_UNAVAILABLE);
		if (request.layout().outputs().size() > MAX_COLOR_ATTACHMENTS) {
			return Route.rejected(Reason.ATTACHMENT_LIMIT, request.layout(), request.targets());
		}
		if (request.targets() == null) return Route.rejected(Reason.NO_TARGET_SNAPSHOT, request.layout(), null);
		if (request.targets().generation() != request.targetGeneration()) {
			return Route.rejected(Reason.STALE_TARGET_SNAPSHOT, request.layout(), request.targets());
		}

		List<Slot> slots = new ArrayList<>();
		Set<String> written = new HashSet<>();
		for (GbufferOutputLayout.Output output : request.layout().outputs()) {
			if (output.targetName().isEmpty()) {
				slots.add(Slot.unused(output.location()));
				continue;
			}
			Optional<String> canonical = GbufferOutputLayout.canonicalTarget(output.targetName().get());
			if (canonical.isEmpty()) return Route.rejected(Reason.INVALID_OUTPUT, request.layout(), request.targets());
			String name = canonical.get();
			if (!written.add(name)) return Route.rejected(Reason.DUPLICATE_OUTPUT, request.layout(), request.targets());
			Optional<PackTargetSet.ViewPair> pair = request.targets().color(name);
			if (pair.isEmpty()) return Route.rejected(Reason.MISSING_TARGET, request.layout(), request.targets());
			boolean alternate = request.alternateOutputs().getOrDefault(name, false);
			PackTargetSet.View selected = pair.get().selected(alternate);
			for (Sampled sampled : request.sampledInputs()) {
				Optional<PackTargetSet.ViewPair> inputPair = request.targets().color(sampled.name());
				if (inputPair.isPresent() && inputPair.get().selected(sampled.alternate()).texture() == selected.texture()) {
					return Route.rejected(Reason.READ_WRITE_ALIAS, request.layout(), request.targets());
				}
			}
			slots.add(Slot.used(output.location(), name, selected.view(), selected.texture(), alternate));
		}
		return Route.active(request.pipelineIdentity(), request.layout(), request.targets(), slots);
	}

	public static Route vanilla() {
		return Route.noOp(Reason.VANILLA);
	}

	public record Request(RenderPipeline pipeline, Object pipelineIdentity,
		GbufferPipelineOverride.Selection selection, boolean packProgramValid,
		GbufferOutputLayout layout, PackTargetSet.ViewSnapshot targets, long targetGeneration,
		Map<String, Boolean> alternateOutputs, List<Sampled> sampledInputs) {
		public Request {
			Objects.requireNonNull(pipeline, "pipeline");
			Objects.requireNonNull(selection, "selection");
			Objects.requireNonNull(layout, "layout");
			alternateOutputs = alternateOutputs == null ? Map.of() : Map.copyOf(alternateOutputs);
			sampledInputs = sampledInputs == null ? List.of() : List.copyOf(sampledInputs);
		}
	}

	public record Sampled(String name, boolean alternate) {
		public Sampled {
			Objects.requireNonNull(name, "name");
			name = GbufferOutputLayout.canonicalTarget(name).orElse(name);
		}
	}

	public record Slot(int location, Optional<String> targetName, Optional<GpuTextureView> view,
		Optional<Object> physicalTarget, boolean alternate) {
		private static Slot unused(int location) {
			return new Slot(location, Optional.empty(), Optional.empty(), Optional.empty(), false);
		}

		private static Slot used(int location, String name, GpuTextureView view, Object physical, boolean alternate) {
			return new Slot(location, Optional.of(name), Optional.of(view), Optional.of(physical), alternate);
		}
	}

	public record Route(boolean active, boolean rejected, Object pipelineIdentity,
		GbufferOutputLayout layout, PackTargetSet.ViewSnapshot targets,
		List<Slot> slots, Reason reason) {
		public Route {
			slots = List.copyOf(slots);
		}

		private static Route noOp(Reason reason) {
			return new Route(false, false, null, null, null, List.of(), reason);
		}

		private static Route rejected(Reason reason, GbufferOutputLayout layout, PackTargetSet.ViewSnapshot targets) {
			return new Route(false, true, null, layout, targets, List.of(), reason);
		}

		private static Route active(Object identity, GbufferOutputLayout layout,
			PackTargetSet.ViewSnapshot targets, List<Slot> slots) {
			return new Route(true, false, identity, layout, targets, slots, Reason.ACTIVE);
		}
	}

	public enum Reason {
		ACTIVE,
		VANILLA,
		NOT_CATALOGED,
		NO_PIPELINE_IDENTITY,
		PACK_PROGRAM_UNAVAILABLE,
		NO_TARGET_SNAPSHOT,
		STALE_TARGET_SNAPSHOT,
		ATTACHMENT_LIMIT,
		INVALID_OUTPUT,
		DUPLICATE_OUTPUT,
		MISSING_TARGET,
		READ_WRITE_ALIAS
	}
}
