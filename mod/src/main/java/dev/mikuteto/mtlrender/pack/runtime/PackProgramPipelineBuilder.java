package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import dev.mikuteto.mtlrender.backend.MetalAccess;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.PackManager;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.ShaderProperties;
import dev.mikuteto.mtlrender.pack.compiler.CompiledShader;
import dev.mikuteto.mtlrender.pack.compiler.GlslCompiler;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import dev.mikuteto.mtlrender.pack.target.RenderTargetPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PackProgramPipelineBuilder {
	public static final String VERTEX_ENTRY = "main_vertex";
	public static final String FRAGMENT_ENTRY = "main_fragment";

	@FunctionalInterface
	public interface StageTranslator {
		CompiledShader translate(Program.Stage stage, String source) throws Exception;
	}

	@FunctionalInterface
	public interface PipelineCreator {
		long create(PackPipelineDesc descriptor);
	}

	@FunctionalInterface
	public interface MeshPipelineCreator {
		long create(MeshPipeline descriptor);
	}

	@FunctionalInterface
	public interface MeshDispatcher {
		boolean dispatch(MetalAccess.MeshDispatch request);
	}

	public record MeshPipeline(String objectMsl, String meshMsl, String fragmentMsl,
		String objectEntry, String meshEntry, String fragmentEntry, PackPipelineDesc descriptor) {
		public MeshPipeline {
			Objects.requireNonNull(objectMsl, "object MSL");
			Objects.requireNonNull(meshMsl, "mesh MSL");
			Objects.requireNonNull(fragmentMsl, "fragment MSL");
			Objects.requireNonNull(objectEntry, "object entry");
			Objects.requireNonNull(meshEntry, "mesh entry");
			Objects.requireNonNull(fragmentEntry, "fragment entry");
			Objects.requireNonNull(descriptor, "descriptor");
		}
	}

	public record Result(Optional<PackProgramArtifact> artifact, Optional<PackProgramFailure> failure,
		Optional<PackPipelineDesc> descriptor) {
		public Result {
			Objects.requireNonNull(artifact, "artifact");
			Objects.requireNonNull(failure, "failure");
			Objects.requireNonNull(descriptor, "descriptor");
			if (artifact.isPresent() == failure.isPresent()) {
				throw new IllegalArgumentException("a pipeline result must contain exactly one outcome");
			}
			if (failure.isPresent() && descriptor.isPresent()) {
				throw new IllegalArgumentException("a failed pipeline must not expose a descriptor");
			}
		}

		public boolean successful() {
			return artifact.isPresent();
		}
	}

	private final StageTranslator translator;
	private final PipelineCreator pipelineCreator;
	private final MeshPipelineCreator meshPipelineCreator;
	private final MeshDispatcher meshDispatcher;

	public PackProgramPipelineBuilder() {
		this((stage, source) -> new GlslCompiler().compile(toCompilerStage(stage),
			List.of(new GlslCompiler.Source(stage.extension(), source)), List.of()),
			MetalAccess::createPackPipeline,
			descriptor -> MetalAccess.createPackMeshPipeline(descriptor.objectMsl(), descriptor.meshMsl(),
				descriptor.fragmentMsl(), descriptor.objectEntry(), descriptor.meshEntry(), descriptor.fragmentEntry(),
				descriptor.descriptor()),
			request -> {
				MetalAccess.MeshFeature feature = MetalAccess.enableMeshFeature();
				return feature != null && feature.dispatch(request);
			});
	}

	public PackProgramPipelineBuilder(GlslCompiler compiler, PipelineCreator pipelineCreator) {
		this((stage, source) -> compiler.compile(toCompilerStage(stage),
			List.of(new GlslCompiler.Source(stage.extension(), source)), List.of()), pipelineCreator,
			descriptor -> MetalAccess.createPackMeshPipeline(descriptor.objectMsl(), descriptor.meshMsl(),
				descriptor.fragmentMsl(), descriptor.objectEntry(), descriptor.meshEntry(), descriptor.fragmentEntry(),
				descriptor.descriptor()), request -> false);
	}

	public PackProgramPipelineBuilder(StageTranslator translator, PipelineCreator pipelineCreator) {
		this(translator, pipelineCreator,
			descriptor -> MetalAccess.createPackMeshPipeline(descriptor.objectMsl(), descriptor.meshMsl(),
				descriptor.fragmentMsl(), descriptor.objectEntry(), descriptor.meshEntry(), descriptor.fragmentEntry(),
				descriptor.descriptor()), request -> false);
	}

	public PackProgramPipelineBuilder(StageTranslator translator, PipelineCreator pipelineCreator,
		MeshPipelineCreator meshPipelineCreator, MeshDispatcher meshDispatcher) {
		this.translator = Objects.requireNonNull(translator, "translator");
		this.pipelineCreator = Objects.requireNonNull(pipelineCreator, "pipeline creator");
		this.meshPipelineCreator = Objects.requireNonNull(meshPipelineCreator, "mesh pipeline creator");
		this.meshDispatcher = Objects.requireNonNull(meshDispatcher, "mesh dispatcher");
	}

	public static PackProgramCache<Result> createCache(PackManager manager) {
		return new PackProgramCache<>(manager, result -> result.artifact().ifPresent(artifact ->
			MetalAccess.destroyPackPipeline(artifact.runtime().renderPipeline())));
	}

	public Result build(PackProgramRequest request, ShaderPack pack, PassKind pass) {
		Objects.requireNonNull(pack, "pack");
		return build(request, pack.targetPlan(), pack.properties(), pass);
	}

	public Optional<Result> buildCached(PackProgramCache<Result> cache, PackProgramRequest request,
		ShaderPack pack, PassKind pass) {
		Objects.requireNonNull(cache, "cache");
		Objects.requireNonNull(pack, "pack");
		return cache.generation(request.dimension()).map(generation -> generation.result(request.program(),
			() -> PackProgramCache.ProgramResult.success(build(request, pack, pass))))
			.flatMap(result -> result.pipeline());
	}

	public Optional<Result> buildCached(PackProgramCache<Result> cache, PackProgramRequest request,
		RenderTargetPlan targetPlan, ShaderProperties properties, PassKind pass) {
		Objects.requireNonNull(cache, "cache");
		return cache.generation(request.dimension()).map(generation -> generation.result(request.program(),
			() -> PackProgramCache.ProgramResult.success(build(request, targetPlan, properties, pass))))
			.flatMap(result -> result.pipeline());
	}

	public Result build(PackProgramRequest request, RenderTargetPlan targetPlan, ShaderProperties properties,
		PassKind pass) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(targetPlan, "target plan");
		Objects.requireNonNull(properties, "properties");
		Objects.requireNonNull(pass, "pass");
		CompiledShader vertex;
		try {
			vertex = translate(request, Program.Stage.VERTEX);
		} catch (Exception exception) {
			return failure(request, Program.Stage.VERTEX, exception);
		}
		CompiledShader fragment;
		try {
			fragment = translate(request, Program.Stage.FRAGMENT);
		} catch (Exception exception) {
			return failure(request, Program.Stage.FRAGMENT, exception);
		}
		CompiledShader geometry = null;
		PackPipelineDesc.LayerInstancing layerInstancing = null;
		Optional<GeometryPathSelector.Result> geometrySelection = Optional.empty();
		GeometryPathSelector.Path selectedGeometryPath = null;
		if (request.source(Program.Stage.GEOMETRY).isPresent()) {
			try {
				geometry = translate(request, Program.Stage.GEOMETRY);
				Reflection.Geometry geometryReflection = geometry.reflection().geometry();
				if (geometryReflection == null) {
					geometry = null;
				} else {
					geometrySelection = Optional.of(selectGeometry(request, geometryReflection));
					GeometryPathSelector.Result selection = geometrySelection.orElseThrow();
					if (selection.selectedPath().isEmpty()) {
						return failure(request, Program.Stage.COMPUTE, selectionFailure(selection));
					}
					selectedGeometryPath = selection.selectedPath().orElseThrow();
					if (selectedGeometryPath == GeometryPathSelector.Path.COMPUTE) {
						return failure(request, Program.Stage.COMPUTE,
							new IllegalArgumentException("compute geometry pipeline is not available"));
					}
				}
				if (selectedGeometryPath == GeometryPathSelector.Path.PASS_THROUGH) {
					geometry = null;
				} else if (selectedGeometryPath == GeometryPathSelector.Path.LAYER_INSTANCED) {
					layerInstancing = new PackPipelineDesc.LayerInstancing(geometryReflection.instanceCount(), 0,
						geometryReflection.layerMapping());
					vertex = new CompiledShader(layerVertexSource(vertex.msl(), layerInstancing.layerMapping()), vertex.reflection());
					geometry = null;
				} else if (selectedGeometryPath == GeometryPathSelector.Path.COMPUTE) {
					geometry = null;
				}
			} catch (Exception exception) {
				return failure(request, Program.Stage.GEOMETRY, exception);
			}
		}
		PackPipelineDesc descriptor;
		try {
			descriptor = describe(request, targetPlan, properties, pass, vertex, fragment,
			PackFunctionConstants.genericFallback(1), Optional.ofNullable(layerInstancing));
		} catch (Exception exception) {
			return failure(request, Program.Stage.FRAGMENT, exception);
		}
		long handle;
		Optional<PackProgramArtifact.MeshMetadata> mesh = Optional.empty();
		try {
			if (geometry == null) {
				handle = pipelineCreator.create(descriptor);
			} else {
				PackProgramArtifact.MeshMetadata metadata = meshMetadata(request, vertex, geometry, fragment);
				MeshPipeline meshPipeline = new MeshPipeline(metadata.objectMsl(), metadata.meshMsl(), fragment.msl(),
					metadata.objectEntry(), metadata.meshEntry(), metadata.fragmentEntry(), descriptor);
				handle = meshPipelineCreator.create(meshPipeline);
				if (handle > 0 && !meshDispatcher.dispatch(metadata.dispatch())) {
					throw new IllegalStateException("mesh dispatch was rejected");
				}
				mesh = Optional.of(metadata);
			}
			if (handle <= 0) throw new IllegalStateException("pack pipeline creation returned an invalid handle");
		} catch (Exception exception) {
			Program.Stage failedStage = geometry == null ? Program.Stage.FRAGMENT : Program.Stage.GEOMETRY;
			if (exception.getMessage() != null && exception.getMessage().contains("compute candidate")) {
				failedStage = Program.Stage.COMPUTE;
			}
			return failure(request, failedStage, exception);
		}
		Map<Program.Stage, String> translated = new EnumMap<>(Program.Stage.class);
		translated.put(Program.Stage.VERTEX, vertex.msl());
		translated.put(Program.Stage.FRAGMENT, fragment.msl());
		if (geometry != null) translated.put(Program.Stage.GEOMETRY, geometry.msl());
		RuntimeProgram runtime = new RuntimeProgram(request.dimension(), request.program(), request.program(), handle);
		PackProgramArtifact artifact = new PackProgramArtifact(request, runtime, translated, mesh,
			Optional.ofNullable(layerInstancing), geometrySelection);
		return new Result(Optional.of(artifact), Optional.empty(), Optional.of(descriptor));
	}

	public PackPipelineDesc describe(PackProgramRequest request, RenderTargetPlan targetPlan,
		ShaderProperties properties, PassKind pass, CompiledShader vertex, CompiledShader fragment) {
		return describe(request, targetPlan, properties, pass, vertex, fragment,
			PackFunctionConstants.genericFallback(1));
	}

	public PackPipelineDesc describe(PackProgramRequest request, RenderTargetPlan targetPlan,
		ShaderProperties properties, PassKind pass, CompiledShader vertex, CompiledShader fragment,
		PackFunctionConstants constants) {
		return describe(request, targetPlan, properties, pass, vertex, fragment, constants, Optional.empty());
	}

	private PackPipelineDesc describe(PackProgramRequest request, RenderTargetPlan targetPlan,
		ShaderProperties properties, PassKind pass, CompiledShader vertex, CompiledShader fragment,
		PackFunctionConstants constants, Optional<PackPipelineDesc.LayerInstancing> layerInstancing) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(targetPlan, "target plan");
		Objects.requireNonNull(properties, "properties");
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(vertex, "vertex");
		Objects.requireNonNull(fragment, "fragment");
		validateStage(vertex, Program.Stage.VERTEX);
		validateStage(fragment, Program.Stage.FRAGMENT);
		if (!vertex.reflection().vertexInputs().isEmpty()) {
			throw new IllegalArgumentException("render program vertex inputs require a non-fullscreen vertex layout");
		}
		List<PackPipelineDesc.Binding> bindings = bindings(vertex.reflection(), fragment.reflection());
		List<ColorTargetState> colors = colors(request, targetPlan, properties, pass, fragment.reflection());
		DepthState depth = depthState(request.program(), pass);
		return new PackPipelineDesc(vertex.msl(), VERTEX_ENTRY, fragment.msl(), FRAGMENT_ENTRY, bindings, null,
			colors, depth.format(), depth.test(), depth.write(), depth.cull(),
			PackPipelineDesc.typedConstants(constants), layerInstancing);
	}

	private static String layerVertexSource(String source, List<Integer> mapping) {
		if (mapping.isEmpty()) throw new IllegalArgumentException("layer mapping is empty");
		String marker = "struct MtlrVertexOut {\n    float4 mtlr_position [[position]];\n";
		if (!source.contains(marker)) throw new IllegalArgumentException("layered vertex output is missing");
		String helper = "static uint mtlr_layer_for_instance(uint instance_id) { switch (instance_id) {";
		for (int index = 0; index < mapping.size(); index++) {
			helper += " case " + index + "u: return " + mapping.get(index) + "u;";
		}
		helper += " default: return 0u; } }\n";
		String result = source.replace(marker, helper + marker.replace("{\n", "{\n    uint mtlr_layer [[render_target_array_index]];\n"));
		int entry = result.indexOf("main_vertex(");
		int close = entry < 0 ? -1 : result.indexOf(") {", entry);
		if (close < 0) throw new IllegalArgumentException("layered vertex entry is missing");
		String argument = "uint mtlr_instance_id [[instance_id]]";
		if (result.charAt(close - 1) == '(') result = result.substring(0, close) + argument + result.substring(close);
		else result = result.substring(0, close) + ", " + argument + result.substring(close);
		String returnMarker = "    return mtlr_out;";
		if (!result.contains(returnMarker)) throw new IllegalArgumentException("layered vertex return is missing");
		return result.replace(returnMarker, "    mtlr_out.mtlr_layer = mtlr_layer_for_instance(mtlr_instance_id);\n" + returnMarker);
	}

	private GeometryPathSelector.Result selectGeometry(PackProgramRequest request, Reflection.Geometry reflection) {
		GeometryPlan.GeometryPresence presence = GeometryPlan.GeometryPresence.PRESENT;
		GeometryPlan.LayerSemantics layers = reflection.layered()
			? GeometryPlan.LayerSemantics.INSTANCE : GeometryPlan.LayerSemantics.NONE;
		GeometryPlan.OutputBudget budget = new GeometryPlan.OutputBudget(
			Math.max(1, reflection.producedVertices()), Math.max(1, reflection.producedPrimitives()));
		GeometryPlan.PrimitiveTopology topology = topology(reflection.outputTopology());
		GeometryPlan.GeometryPath initial = reflection.passThrough() ? GeometryPlan.GeometryPath.PASS_THROUGH
			: reflection.layered() ? GeometryPlan.GeometryPath.LAYER_INSTANCED
			: budget.vertices() > GeometryPlan.MAX_MESH_VERTICES || budget.primitives() > GeometryPlan.MAX_MESH_PRIMITIVES
				? GeometryPlan.GeometryPath.COMPUTE : GeometryPlan.GeometryPath.MESH;
		GeometryPlan plan = new GeometryPlan(presence, topology, budget, Set.of(),
			new GeometryPlan.LayerMetadata(layers, layers == GeometryPlan.LayerSemantics.INSTANCE ? reflection.instanceCount() : 0),
			initial, new GeometryPlan.StageIdentities(Map.of(Program.Stage.VERTEX, "vertex",
				Program.Stage.FRAGMENT, "fragment", Program.Stage.GEOMETRY, "geometry")),
			GeometryPlan.EntryPoints.generate(request.pack() + "\u0000" + request.dimension() + "\u0000" + request.program()));
		Optional<GeometryComputePlan> compute = Optional.empty();
		if (layers == GeometryPlan.LayerSemantics.NONE) {
			GeometryPlan computeGeometry = new GeometryPlan(presence, topology, budget, Set.of(),
				new GeometryPlan.LayerMetadata(GeometryPlan.LayerSemantics.NONE, 0), GeometryPlan.GeometryPath.COMPUTE,
				plan.stages(), plan.entryPoints());
			compute = Optional.of(GeometryComputePlan.build(new GeometryComputePlan.Context(request.pack(), request.dimension(), request.program(), "geometry"),
				computeGeometry, new GeometryComputePlan.Input(1, 16, 12), new GeometryComputePlan.Limits(1_000_000), true));
		}
		GeometryPathSelector.AnalyzerResult analyzer = reflection.passThrough()
			? GeometryPathSelector.AnalyzerResult.passThrough()
			: reflection.layered() ? GeometryPathSelector.AnalyzerResult.layerInstance()
			: GeometryPathSelector.AnalyzerResult.general();
		GeometryPathSelector.MeshContract mesh = new GeometryPathSelector.MeshContract(
			Set.of(GeometryPlan.PrimitiveTopology.POINTS, GeometryPlan.PrimitiveTopology.LINES, GeometryPlan.PrimitiveTopology.TRIANGLES), Set.of());
		return GeometryPathSelector.select(new GeometryPathSelector.Input(request.pack(), request.dimension(),
			new GeometryPathSelector.ProgramIdentity(GeometryPathSelector.ProgramKind.RENDER, request.program()), plan,
			analyzer, mesh, compute), (path, ignored) -> GeometryPathSelector.Attempt.success());
	}

	private static IllegalArgumentException selectionFailure(GeometryPathSelector.Result selection) {
		String diagnostics = selection.diagnostics().stream()
			.map(value -> value.path() + "=" + value.reason() + ":" + value.detail()).toList().toString();
		return new IllegalArgumentException("geometry selection failed; pack=" + selection.pack()
			+ ", dimension=" + selection.dimension() + ", program=" + selection.program().name()
			+ ", pattern=" + selection.pattern() + ", output=" + selection.outputBudget()
			+ ", sideEffects=" + selection.sideEffects() + ", diagnostics=" + diagnostics);
	}

	private PackProgramArtifact.MeshMetadata meshMetadata(PackProgramRequest request, CompiledShader vertex,
		CompiledShader geometry, CompiledShader fragment) {
		Reflection.Geometry reflection = geometry.reflection().geometry();
		if (reflection == null || reflection.passThrough()) {
			throw new IllegalArgumentException("mesh lowering metadata is missing");
		}
		if (reflection.producedVertices() <= 0 || reflection.producedPrimitives() <= 0
			|| reflection.producedVertices() > GeometryPlan.MAX_MESH_VERTICES
			|| reflection.producedPrimitives() > GeometryPlan.MAX_MESH_PRIMITIVES) {
			throw new IllegalArgumentException("geometry output is outside the mesh budget; compute candidate required");
		}
		String hash = meshHash(request.pack(), request.dimension(), request.program());
		String objectEntry = "mtlr_object_" + hash;
		String meshEntry = "mtlr_mesh_" + hash;
		String fragmentEntry = "mtlr_fragment_" + hash;
		String marker = "[[mesh]]";
		int split = geometry.msl().indexOf(marker);
		if (split < 0) throw new IllegalArgumentException("mesh entry source is missing; compute candidate required");
		int line = geometry.msl().lastIndexOf('\n', split);
		if (line < 0) throw new IllegalArgumentException("mesh entry source has no declaration; compute candidate required");
		String objectMsl = geometry.msl().substring(0, line);
		String meshMsl = geometry.msl().substring(line + 1);
		GeometryPlan.PrimitiveTopology topology = topology(reflection.outputTopology());
		int payload = 16;
		int vertexStride = 16;
		int primitiveStride = topology == GeometryPlan.PrimitiveTopology.POINTS ? 4 : 12;
		long vertexBytes = (long) reflection.producedVertices() * vertexStride;
		long primitiveBytes = (long) reflection.producedPrimitives() * primitiveStride;
		long primitiveOffset = 4096 + vertexBytes;
		long resourceLength = primitiveOffset + primitiveBytes;
		if (resourceLength > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("mesh output layout exceeds dispatch range; compute candidate required");
		}
		MetalAccess.MeshDispatch dispatch = new MetalAccess.MeshDispatch(1, 1, 1, 256, 1, 1,
			payload, reflection.producedVertices(), reflection.producedPrimitives(), 1, 0, resourceLength,
			0, 4096, vertexBytes, primitiveOffset, primitiveBytes, 1, 1, 1);
		return new PackProgramArtifact.MeshMetadata(objectMsl, meshMsl, objectEntry, meshEntry, fragmentEntry,
			topology, payload, reflection.producedVertices(), reflection.producedPrimitives(), vertexStride, primitiveStride,
			allResources(vertex.reflection(), geometry.reflection(), fragment.reflection()), fragment.reflection().fragmentOutputs(),
			bindings(vertex.reflection(), fragment.reflection()), dispatch);
	}

	private static List<Reflection.Resource> allResources(Reflection... reflections) {
		Map<String, Reflection.Resource> result = new LinkedHashMap<>();
		for (Reflection reflection : reflections) {
			for (Reflection.Resource resource : reflection.resources()) result.putIfAbsent(resource.kind() + ":" + resource.name(), resource);
		}
		return List.copyOf(result.values());
	}

	private static GeometryPlan.PrimitiveTopology topology(String value) {
		return switch (value) {
			case "points" -> GeometryPlan.PrimitiveTopology.POINTS;
			case "lines", "line_strip" -> GeometryPlan.PrimitiveTopology.LINES;
			default -> GeometryPlan.PrimitiveTopology.TRIANGLES;
		};
	}

	private static String meshHash(String pack, String dimension, String program) {
		long hash = 0xcbf29ce484222325L;
		for (String value : List.of(pack, dimension, program)) {
			for (byte current : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
				hash ^= Byte.toUnsignedLong(current);
				hash *= 0x100000001b3L;
			}
			hash ^= 0xffL;
			hash *= 0x100000001b3L;
		}
		return String.format(java.util.Locale.ROOT, "%016x", hash);
	}

	private CompiledShader translate(PackProgramRequest request, Program.Stage stage) throws Exception {
		String source = request.source(stage).orElseThrow(() -> new IllegalArgumentException(
			"missing " + stage.extension() + " source"));
		return translator.translate(stage, source);
	}

	private static List<PackPipelineDesc.Binding> bindings(Reflection vertex, Reflection fragment) {
		Map<BindingKey, PackPipelineDesc.Binding> result = new LinkedHashMap<>();
		addBindings(result, vertex, PackPipelineDesc.STAGE_VERTEX);
		addBindings(result, fragment, PackPipelineDesc.STAGE_FRAGMENT);
		List<PackPipelineDesc.Binding> bindings = new ArrayList<>(result.values());
		bindings.sort(Comparator.comparing((PackPipelineDesc.Binding value) -> value.kind().ordinal())
			.thenComparingInt(PackPipelineDesc.Binding::slot).thenComparing(PackPipelineDesc.Binding::name));
		return List.copyOf(bindings);
	}

	private static void addBindings(Map<BindingKey, PackPipelineDesc.Binding> result, Reflection reflection, int stage) {
		for (Reflection.Resource resource : reflection.resources()) {
			PackPipelineDesc.Kind kind = kind(resource.kind());
			if (kind == null) {
				throw new IllegalArgumentException("unsupported render resource " + resource.name());
			}
			BindingKey key = new BindingKey(kind, resource.name());
			PackPipelineDesc.Binding old = result.get(key);
			if (old != null && old.slot() != resource.binding()) {
				throw new IllegalArgumentException("binding changed between shader stages: " + resource.name());
			}
			int stages = stage | (old == null ? 0 : old.stages());
			result.put(key, new PackPipelineDesc.Binding(resource.name(), kind, resource.binding(), stages));
		}
	}

	private static PackPipelineDesc.Kind kind(int resourceKind) {
		return switch (resourceKind) {
			case 0, 1 -> PackPipelineDesc.Kind.BUFFER;
			case 2, 3 -> PackPipelineDesc.Kind.TEXTURE;
			case 4 -> PackPipelineDesc.Kind.SAMPLER;
			default -> null;
		};
	}

	private static List<ColorTargetState> colors(PackProgramRequest request, RenderTargetPlan targetPlan,
		ShaderProperties properties, PassKind pass, Reflection reflection) {
		RenderTargetPlan.Pass targetPass = findPass(targetPlan, request, pass);
		Set<Integer> indices = new LinkedHashSet<>();
		if (targetPass != null) {
			indices.addAll(targetPass.writes());
		}
		Map<Integer, Integer> locations = new LinkedHashMap<>();
		for (Reflection.FragmentOutput output : reflection.fragmentOutputs()) {
			if (output.location() < 0 || output.colortex() < 0) {
				throw new IllegalArgumentException("invalid fragment output location");
			}
			indices.add(output.colortex());
			locations.put(output.location(), output.colortex());
		}
		if (indices.isEmpty()) {
			return List.of();
		}
		int maxLocation = locations.keySet().stream().max(Integer::compareTo).orElse(-1);
		if (maxLocation < 0) {
			maxLocation = indices.stream().max(Integer::compareTo).orElseThrow();
		}
		if (maxLocation >= 8) {
			throw new IllegalArgumentException("render program uses more than eight color attachments");
		}
		List<ColorTargetState> result = new ArrayList<>();
		for (int location = 0; location <= maxLocation; location++) {
			Integer targetIndex = locations.get(location);
			if (targetIndex == null) {
				targetIndex = location;
			}
			if (!indices.contains(targetIndex)) {
				result.add(null);
				continue;
			}
			RenderTargetPlan.ColorTarget target = targetPlan.color(targetIndex);
			BlendFunction blend = blend(properties, request.program(), targetIndex);
			result.add(new ColorTargetState(Optional.ofNullable(blend), target.metalFormat(), ColorTargetState.WRITE_ALL));
		}
		return java.util.Collections.unmodifiableList(result);
	}

	private static RenderTargetPlan.Pass findPass(RenderTargetPlan targetPlan, PackProgramRequest request, PassKind pass) {
		RenderTargetPlan.Pass fallback = null;
		for (RenderTargetPlan.Pass candidate : targetPlan.passes()) {
			if (!candidate.dimension().equals(request.dimension())) {
				continue;
			}
			if (candidate.name().equals(request.program())) {
				return candidate;
			}
			if (candidate.name().equals(pass.sourceName()) || candidate.name().startsWith(pass.sourceName())) {
				fallback = candidate;
			}
		}
		return fallback;
	}

	private static BlendFunction blend(ShaderProperties properties, String program, int targetIndex) {
		Map<String, String> perBuffer = properties.bufferBlends().getOrDefault(program, Map.of());
		String value = null;
		for (String name : targetNames(targetIndex)) {
			if (perBuffer.containsKey(name)) {
				value = perBuffer.get(name);
				break;
			}
		}
		if (value == null) {
			value = properties.blends().get(program);
		}
		return parseBlend(value);
	}

	private static List<String> targetNames(int index) {
		List<String> names = new ArrayList<>();
		names.add("colortex" + index);
		List<String> legacy = List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");
		if (index < legacy.size()) {
			names.add(legacy.get(index));
		}
		return names;
	}

	private static BlendFunction parseBlend(String value) {
		if (value == null || value.equals("off")) {
			return null;
		}
		String[] parts = value.trim().split("\\s+");
		if (parts.length != 4) {
			throw new IllegalArgumentException("blend state requires four factors");
		}
		return new BlendFunction(factor(parts[0]), factor(parts[1]), factor(parts[2]), factor(parts[3]));
	}

	private static BlendFactor factor(String name) {
		try {
			return BlendFactor.valueOf(name);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("unsupported blend factor " + name, exception);
		}
	}

	private static DepthState depthState(String program, PassKind pass) {
		boolean fullscreen = switch (pass) {
			case SETUP, BEGIN, SHADOWCOMP, PREPARE, DEFERRED, COMPOSITE, FINAL -> true;
		};
		if (fullscreen || program.startsWith("composite") || program.startsWith("deferred") || program.equals("final")) {
			return new DepthState(null, null, false, PackPipelineDesc.CullMode.NONE);
		}
		if (program.startsWith("shadow")) {
			return new DepthState(GpuFormat.D32_FLOAT, CompareOp.LESS_THAN_OR_EQUAL, true, PackPipelineDesc.CullMode.NONE);
		}
		return new DepthState(GpuFormat.D32_FLOAT, CompareOp.LESS_THAN_OR_EQUAL, true, PackPipelineDesc.CullMode.BACK);
	}

	private static void validateStage(CompiledShader shader, Program.Stage expected) {
		if (shader.msl().isEmpty() || shader.reflection().stage() != expected.ordinal()) {
			throw new IllegalArgumentException("translated " + expected.extension() + " reflection does not match its stage");
		}
	}

	private static Result failure(PackProgramRequest request, Program.Stage stage, Exception exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			message = exception.getClass().getSimpleName();
		}
		PackProgramFailure failure = new PackProgramFailure(request.pack(), request.dimension(), request.program(), stage, message);
		return new Result(Optional.empty(), Optional.of(failure), Optional.empty());
	}

	private static GlslCompiler.Stage toCompilerStage(Program.Stage stage) {
		return switch (stage) {
			case VERTEX -> GlslCompiler.Stage.VERTEX;
			case GEOMETRY -> GlslCompiler.Stage.GEOMETRY;
			case TESS_CONTROL -> GlslCompiler.Stage.TESSELLATION_CONTROL;
			case TESS_EVALUATION -> GlslCompiler.Stage.TESSELLATION_EVALUATION;
			case FRAGMENT -> GlslCompiler.Stage.FRAGMENT;
			case COMPUTE -> GlslCompiler.Stage.COMPUTE;
		};
	}

	private record BindingKey(PackPipelineDesc.Kind kind, String name) {
	}

	private record DepthState(GpuFormat format, CompareOp test, boolean write, PackPipelineDesc.CullMode cull) {
	}
}
