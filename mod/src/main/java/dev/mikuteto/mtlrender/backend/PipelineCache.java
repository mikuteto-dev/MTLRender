package dev.mikuteto.mtlrender.backend;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferAttachmentRouter;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferOutputLayout;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferPipelineCatalog;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferPipelineOverride;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferProgramResolver;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferShaderSource;
import dev.mikuteto.mtlrender.pack.gbuffer.GbufferStateOverride;
import dev.mikuteto.mtlrender.pack.runtime.PackRuntimeService;
import dev.mikuteto.mtlrender.terrain.TerrainMeshShader;
import org.slf4j.Logger;

final class PipelineCache implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<RenderPipeline, UnaryOperator<ShaderSource>> SOURCE_OVERRIDES = new ConcurrentHashMap<>();
	private static final Map<RenderPipeline, GbufferRegistration> GBUFFER_OVERRIDES = new ConcurrentHashMap<>();

	private final NativeGpu gpu;
	private final ShaderSource defaultSource;
	private final ShaderTranslator translator = new ShaderTranslator();
	private final GbufferPipelineOverride gbufferOverride;
	private final Map<RenderPipeline, CachedPipeline> pipelines = new IdentityHashMap<>();
	private final Map<RenderPipeline, MetalPipeline> meshPipelines = new IdentityHashMap<>();

	PipelineCache(NativeGpu gpu, ShaderSource defaultSource, GbufferPipelineOverride gbufferOverride) {
		this.gpu = gpu;
		this.defaultSource = defaultSource;
		this.gbufferOverride = gbufferOverride;
	}

	PipelineCache(NativeGpu gpu, ShaderSource defaultSource) {
		this(gpu, defaultSource, null);
	}

	static void overrideSource(RenderPipeline pipeline, UnaryOperator<ShaderSource> source) {
		SOURCE_OVERRIDES.put(pipeline, source);
	}

	static void overrideGbuffer(RenderPipeline pipeline, GbufferStateOverride override, java.util.List<Integer> outputBuffers) {
		GBUFFER_OVERRIDES.put(pipeline, new GbufferRegistration(override, java.util.List.copyOf(outputBuffers)));
	}

	MetalPipeline get(RenderPipeline pipeline) {
		return precompile(pipeline, defaultSource);
	}

	GbufferAttachmentRouter.Route gbufferRoute(RenderPipeline pipeline, MetalPipeline compiled) {
		if (!GbufferPipelineCatalog.find(pipeline).isPresent() || !compiled.isValid()) {
			return GbufferAttachmentRouter.vanilla();
		}
		CachedPipeline cached = pipelines.get(pipeline);
		if (cached == null || cached.pipeline() != compiled) return GbufferAttachmentRouter.vanilla();
		GbufferPipelineOverride.Selection selection = cached.selection();
		if (!selection.active()) return GbufferAttachmentRouter.vanilla();
		try {
			PackRuntimeService service = PackRuntimeService.get();
			GbufferProgramResolver resolver = new GbufferProgramResolver(service.packs()::active, service.packs()::version);
			GbufferProgramResolver.Resolution resolution = resolver.resolve(pipeline, selection.dimension()).orElse(null);
			if (resolution == null || !resolution.resolved()) {
				LOGGER.warn("Gbuffer program unavailable for {} (dimension={}, reason={}); using vanilla attachments",
					pipeline.getLocation(), selection.dimension(), resolution == null ? "missing" : resolution.reason());
				return new GbufferAttachmentRouter().route(new GbufferAttachmentRouter.Request(
					pipeline, compiled, selection, false, GbufferOutputLayout.vanilla(), null, selection.version(), null, null));
			}
			GbufferOutputLayout layout = GbufferProgramResolver.attachOutputLayout(
				resolution.program(), service.activePack().orElseThrow().pack().properties(), service.activePack().orElseThrow().pack().targetPlan()
			).outputLayout();
			PackRuntimeService.RuntimeResources resources = service.resources();
			return new GbufferAttachmentRouter().route(new GbufferAttachmentRouter.Request(
				pipeline, compiled, selection, true, layout,
				resources.targets().map(value -> value.snapshot()).orElse(null), selection.version(), null, null));
		} catch (RuntimeException exception) {
			LOGGER.warn("Gbuffer route failed for {} (pack={}, dimension={}); using vanilla attachments: {}",
				pipeline.getLocation(), selection.version(), selection.dimension(), exception.getMessage());
			return GbufferAttachmentRouter.vanilla();
		}
	}

	MetalPipeline precompile(RenderPipeline pipeline, ShaderSource source) {
		ShaderSource base = source == null ? defaultSource : source;
		UnaryOperator<ShaderSource> override = SOURCE_OVERRIDES.get(pipeline);
		GbufferPipelineOverride installed = gbufferOverride == null ? MetalAccess.gbufferPipelineOverride() : gbufferOverride;
		GbufferPipelineOverride.Selection selection = installed == null
			? new GbufferPipelineOverride.Selection(base, false, Long.MIN_VALUE, "")
			: installed.select(pipeline, base);
		CacheKey key = new CacheKey(selection.active(), selection.version(), selection.dimension());
		CachedPipeline cached = pipelines.get(pipeline);
		if (cached == null || !cached.key().equals(key)) {
			ShaderSource selected = override == null ? selection.source() : override.apply(selection.source());
			GbufferRegistration gbuffer = GBUFFER_OVERRIDES.get(pipeline);
			MetalPipeline compiled = compile(pipeline, selected, gbuffer, selection.active());
			if (cached != null) destroy(cached.pipeline());
			cached = new CachedPipeline(key, compiled, selection);
			pipelines.put(pipeline, cached);
		}
		return cached.pipeline();
	}

	MetalPipeline precompileMesh(RenderPipeline pipeline, TerrainMeshShader.Program program) {
		MetalPipeline cached = meshPipelines.get(pipeline);
		if (cached == null) {
			ShaderSource base = defaultSource;
			UnaryOperator<ShaderSource> override = SOURCE_OVERRIDES.get(pipeline);
			cached = compileMesh(pipeline, override == null ? base : override.apply(base), program);
			meshPipelines.put(pipeline, cached);
		}
		return cached;
	}

	void clear() {
		pipelines.values().forEach(value -> destroy(value.pipeline()));
		meshPipelines.values().forEach(pipeline -> destroy(pipeline));
		pipelines.clear();
		meshPipelines.clear();
		translator.clear();
	}

	@Override
	public void close() {
		clear();
		SOURCE_OVERRIDES.clear();
		GBUFFER_OVERRIDES.clear();
		translator.close();
	}

	private void destroy(MetalPipeline pipeline) {
		if (pipeline.isValid()) {
			gpu.destroy(NativeGpu.PIPELINE, pipeline.handle());
		}
	}

	private record CacheKey(boolean active, long version, String dimension) {
	}

	private record CachedPipeline(CacheKey key, MetalPipeline pipeline, GbufferPipelineOverride.Selection selection) {
	}

	private MetalPipeline compile(RenderPipeline pipeline, ShaderSource source, GbufferRegistration gbuffer, boolean packGbuffer) {
		VertexLayout layout = VertexLayout.of(pipeline);
		try {
			ShaderSource selected = gbuffer == null || gbuffer.override().alphaTest().isEmpty()
				? source
				: GbufferShaderSource.withAlphaTest(source, pipeline.getFragmentShader(), gbuffer.override().alphaTest().get());
			ShaderTranslator.Translation translation = translator.translate(pipeline, layout, selected);
			int[] descriptor = PipelineDescriptor.encode(pipeline, layout,
				gbuffer == null ? null : gbuffer.override(),
				gbuffer == null ? java.util.List.of() : gbuffer.outputBuffers());
			long handle = gpu.createPipeline(translation.vertex(), translation.fragment(), SpirvShader.ENTRY_POINT, descriptor);
			if (handle == 0) {
				LOGGER.error("Couldn't create Metal pipeline {}: {}", pipeline.getLocation(), gpu.lastError());
				return MetalPipeline.INVALID;
			}
			return new MetalPipeline(handle, translation.bindings(), MetalPipeline.Kind.RASTER, packGbuffer);
		} catch (ShaderCompileException e) {
			LOGGER.error("Couldn't translate pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return MetalPipeline.INVALID;
		}
	}

	private MetalPipeline compileMesh(RenderPipeline pipeline, ShaderSource source, TerrainMeshShader.Program program) {
		VertexLayout layout = VertexLayout.of(pipeline);
		try {
			ShaderTranslator.Translation translation = translator.translate(pipeline, layout, source);
			int[] descriptor = PipelineDescriptor.encode(pipeline, layout);
			TerrainMeshShader.Sources sources = program.sources();
			TerrainMeshShader.EntryPoints entryPoints = program.entryPoints();
			long handle = gpu.createMeshPipeline(
				sources.object(), sources.mesh(), translation.fragment(),
				entryPoints.object(), entryPoints.mesh(), SpirvShader.ENTRY_POINT, descriptor
			);
			if (handle == 0) {
				LOGGER.error("Couldn't create Metal mesh pipeline {}: {}", pipeline.getLocation(), gpu.lastError());
				return MetalPipeline.invalid(MetalPipeline.Kind.MESH);
			}
			return new MetalPipeline(handle, translation.bindings(), MetalPipeline.Kind.MESH, false);
		} catch (ShaderCompileException e) {
			LOGGER.error("Couldn't translate mesh pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return MetalPipeline.invalid(MetalPipeline.Kind.MESH);
		}
	}

	private record GbufferRegistration(GbufferStateOverride override, java.util.List<Integer> outputBuffers) {
	}
}
