package dev.mikuteto.mtlrender.pack.gbuffer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class GbufferProgramResolver {
	private final Supplier<Optional<ShaderPack>> activePack;
	private final LongSupplier packVersion;

	public GbufferProgramResolver() {
		this(PackManager.get()::active, PackManager.get()::version);
	}

	public GbufferProgramResolver(Supplier<Optional<ShaderPack>> activePack, LongSupplier packVersion) {
		this.activePack = activePack;
		this.packVersion = packVersion;
	}

	public Optional<Resolution> resolve(RenderPipeline pipeline, ClientLevel level) {
		if (level == null) return Optional.of(Resolution.failure(pipeline, Reason.NO_LEVEL, packVersion.getAsLong()));
		return resolve(pipeline, level.dimension().identifier().toString());
	}

	public Optional<Resolution> resolve(RenderPipeline pipeline, String dimension) {
		GbufferPipelineSpec spec = GbufferPipelineCatalog.find(pipeline).orElse(null);
		if (spec == null) return Optional.of(Resolution.failure(pipeline, Reason.NO_SPEC, packVersion.getAsLong()));
		Optional<ShaderPack> pack = activePack.get();
		if (pack.isEmpty()) return Optional.of(Resolution.failure(pipeline, Reason.NO_PACK, packVersion.getAsLong()));
		if (dimension == null || dimension.isBlank()) {
			return Optional.of(Resolution.failure(pipeline, Reason.NO_DIMENSION, packVersion.getAsLong()));
		}
		long version = packVersion.getAsLong();
		try {
			Optional<Program> program = pack.get().programSet(dimension).resolve(spec.program());
			if (program.isEmpty()) return Optional.of(Resolution.failure(pipeline, Reason.NO_USABLE_FALLBACK, version));
			return Optional.of(new Resolution(pipeline, spec, program.get(), version, Reason.RESOLVED));
		} catch (RuntimeException exception) {
			return Optional.of(Resolution.failure(pipeline, Reason.UNAVAILABLE, version));
		}
	}

	public Map<RenderPipeline, Resolution> resolveLevel(String dimension) {
		Map<RenderPipeline, Resolution> result = new IdentityHashMap<>();
		for (RenderPipeline pipeline : GbufferPipelineCatalog.levelPipelines()) {
			resolve(pipeline, dimension).ifPresent(value -> result.put(pipeline, value));
		}
		return Collections.unmodifiableMap(result);
	}

	public GbufferShaderSource source(RenderPipeline pipeline, String dimension, ShaderSource vanilla) {
		return source(pipeline, dimension, vanilla, vanillaIdentifier(pipeline, true), vanillaIdentifier(pipeline, false));
	}

	public GbufferShaderSource source(RenderPipeline pipeline, ClientLevel level, ShaderSource vanilla) {
		return source(pipeline, level == null ? (String) null : level.dimension().identifier().toString(), vanilla);
	}

	public GbufferShaderSource source(RenderPipeline pipeline, String dimension, ShaderSource vanilla,
		Identifier vanillaVertex, Identifier vanillaFragment) {
		Optional<Resolution> resolution = resolve(pipeline, dimension);
		if (resolution.isPresent() && resolution.get().resolved()) {
			Program program = resolution.get().program();
			return new GbufferShaderSource(vanilla, vanillaVertex, vanillaFragment,
				synthetic(pipeline, "vertex"), synthetic(pipeline, "fragment"),
				program.source(Program.Stage.VERTEX).orElse(""),
				program.source(Program.Stage.FRAGMENT).orElse(""),
				resolution.get().spec().inputProfile());
		}
		return new GbufferShaderSource(vanilla, vanillaVertex, vanillaFragment,
			synthetic(pipeline, "vertex"), synthetic(pipeline, "fragment"),
			vanilla.get(vanillaVertex, com.mojang.blaze3d.shaders.ShaderType.VERTEX),
			vanilla.get(vanillaFragment, com.mojang.blaze3d.shaders.ShaderType.FRAGMENT));
	}

	public static Program attachOutputLayout(Program program, ShaderProperties properties, RenderTargetPlan plan) {
		if (!program.isRenderProgram()) return program;
		String fragment = program.source(Program.Stage.FRAGMENT).orElse("");
		GbufferOutputLayout layout = GbufferOutputLayout.from(fragment, properties, plan);
		return program.withOutputLayout(layout);
	}

	private static Identifier synthetic(RenderPipeline pipeline, String stage) {
		Identifier location = pipeline.getLocation();
		String path = location.getNamespace() + "/" + location.getPath().replace('/', '_');
		return Identifier.fromNamespaceAndPath("mtlrender", "gbuffer/" + path + "/" + stage);
	}

	private static Identifier vanillaIdentifier(RenderPipeline pipeline, boolean vertex) {
		return vertex ? pipeline.getVertexShader() : pipeline.getFragmentShader();
	}

	public enum Reason {
		RESOLVED,
		NO_SPEC,
		NO_PACK,
		NO_LEVEL,
		NO_DIMENSION,
		NO_USABLE_FALLBACK,
		UNAVAILABLE
	}

	public record Resolution(RenderPipeline pipeline, GbufferPipelineSpec spec, Program program, long version, Reason reason) {
		private static Resolution failure(RenderPipeline pipeline, Reason reason, long version) {
			return new Resolution(pipeline, null, null, version, reason);
		}

		public boolean resolved() {
			return reason == Reason.RESOLVED;
		}
	}
}
