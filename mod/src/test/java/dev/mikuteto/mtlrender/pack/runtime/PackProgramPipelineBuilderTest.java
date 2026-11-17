package dev.mikuteto.mtlrender.pack.runtime;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import dev.mikuteto.mtlrender.backend.PackPipelineDesc;
import dev.mikuteto.mtlrender.pack.Program;
import dev.mikuteto.mtlrender.pack.ShaderPack;
import dev.mikuteto.mtlrender.pack.compiler.CompiledShader;
import dev.mikuteto.mtlrender.pack.compiler.GlslCompileException;
import dev.mikuteto.mtlrender.pack.compiler.Reflection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackProgramPipelineBuilderTest {
	@Test
	void buildsFullscreenDescriptorFromTargetsBlendStateAndReflection() throws Exception {
		ShaderPack pack = pack("const int colortex0Format = RGBA16F;\n");
		PackProgramRequest request = request("composite", true);
		AtomicReference<PackPipelineDesc> captured = new AtomicReference<>();
		AtomicInteger creations = new AtomicInteger();
		PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder(
			(stage, source) -> compiled(stage, stage == Program.Stage.VERTEX
				? List.of(new Reflection.Resource("ubo", 0, 0, 0, 3, false, "constant", 0, ""),
					new Reflection.Resource("albedo", 2, 2, 0, 2, false, "constant", 0, ""))
				: List.of(new Reflection.Resource("colorSampler", 4, 0, 0, 4, false, "constant", 0, "")),
				stage == Program.Stage.FRAGMENT ? List.of(new Reflection.FragmentOutput("color", 0, 0, 0)) : List.of()),
			descriptor -> {
				creations.incrementAndGet();
				captured.set(descriptor);
				return 7L;
			});

		PackProgramPipelineBuilder.Result result = builder.build(request, pack.targetPlan(), pack.properties(), PassKind.COMPOSITE);
		assertTrue(result.successful());
		assertTrue(result.failure().isEmpty());
		assertEquals(1, creations.get());
		PackPipelineDesc descriptor = captured.get();
		assertEquals(GpuFormat.RGBA16_FLOAT, descriptor.colorAttachments().getFirst().format());
		assertEquals(Optional.of(new com.mojang.blaze3d.pipeline.BlendFunction(
			com.mojang.blaze3d.platform.BlendFactor.ONE, com.mojang.blaze3d.platform.BlendFactor.ZERO,
			com.mojang.blaze3d.platform.BlendFactor.ONE, com.mojang.blaze3d.platform.BlendFactor.ZERO)),
			descriptor.colorAttachments().getFirst().blendFunction());
		assertNull(descriptor.depthFormat());
		assertNull(descriptor.depthTest());
		assertFalse(descriptor.depthWrite());
		assertEquals(PackPipelineDesc.CullMode.NONE, descriptor.cullMode());
		assertNull(descriptor.vertexLayout());
		assertEquals(List.of("albedo", "colorSampler", "ubo"), descriptor.bindings().stream()
			.map(PackPipelineDesc.Binding::name).sorted().toList());
		assertEquals(PackPipelineDesc.STAGE_VERTEX, descriptor.bindings().stream()
			.filter(binding -> binding.name().equals("ubo")).findFirst().orElseThrow().stages());
		assertEquals(PackPipelineDesc.STAGE_FRAGMENT, descriptor.bindings().stream()
			.filter(binding -> binding.name().equals("colorSampler")).findFirst().orElseThrow().stages());
	}

	@Test
	void reportsVertexAndFragmentFailuresWithTheirStage() throws Exception {
		ShaderPack pack = pack("");
		PackProgramRequest request = request("composite", false);
		for (Program.Stage failing : List.of(Program.Stage.VERTEX, Program.Stage.FRAGMENT)) {
			PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder((stage, source) -> {
				if (stage == failing) {
					throw new GlslCompileException(stage.extension() + " failed\nsecond line");
				}
				return compiled(stage, List.of(), List.of());
			}, descriptor -> 9L);

			PackProgramPipelineBuilder.Result result = builder.build(request, pack.targetPlan(), pack.properties(), PassKind.COMPOSITE);
			assertFalse(result.successful());
			PackProgramFailure failure = result.failure().orElseThrow();
			assertEquals("test-pack", failure.pack());
			assertEquals("world0", failure.dimension());
			assertEquals("composite", failure.program());
			assertEquals(failing, failure.stage());
			assertEquals(failing.extension() + " failed", failure.firstError());
		}
	}

	@Test
	void keepsDeferredStagesInTheSuccessfulArtifact() throws Exception {
		ShaderPack pack = pack("");
		PackProgramRequest request = request("composite", true);
		PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder(
			(stage, source) -> compiled(stage, List.of(), List.of()), descriptor -> 11L);

		PackProgramPipelineBuilder.Result result = builder.build(request, pack.targetPlan(), pack.properties(), PassKind.COMPOSITE);
		PackProgramArtifact artifact = result.artifact().orElseThrow();
		assertEquals("geometry", artifact.request().source(Program.Stage.GEOMETRY).orElseThrow());
		assertEquals("tess-control", artifact.request().source(Program.Stage.TESS_CONTROL).orElseThrow());
		assertEquals("tess-evaluation", artifact.request().source(Program.Stage.TESS_EVALUATION).orElseThrow());
		assertEquals(SetOfStages.render(), artifact.translatedSources().keySet());
	}

	@Test
	void createsOneMeshPipelineAndDispatchesValidatedMetadata() throws Exception {
		ShaderPack pack = pack("");
		PackProgramRequest request = request("composite", true);
		AtomicInteger pipelines = new AtomicInteger();
		AtomicInteger dispatches = new AtomicInteger();
		PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder(
			(stage, source) -> stage == Program.Stage.GEOMETRY
				? meshCompiled(stage, 256, 512)
				: compiled(stage, List.of(new Reflection.Resource("mesh-resource", 2, 2, 0, 0, false, "device", 0, "")),
					stage == Program.Stage.FRAGMENT ? List.of(new Reflection.FragmentOutput("color", 0, 0, 0)) : List.of()),
			descriptor -> 3L,
			mesh -> {
				pipelines.incrementAndGet();
				assertEquals("mtlr_object_", mesh.objectEntry().substring(0, 12));
				return 8L;
			},
			dispatch -> {
				dispatches.incrementAndGet();
				assertEquals(256, dispatch.vertexCount());
				assertEquals(512, dispatch.primitiveCount());
				assertEquals(4096, dispatch.vertexBytes());
				assertEquals(6144, dispatch.primitiveBytes());
				return true;
			});

		PackProgramPipelineBuilder.Result result = builder.build(request, pack.targetPlan(), pack.properties(), PassKind.COMPOSITE);
		PackProgramArtifact artifact = result.artifact().orElseThrow();
		assertEquals(1, pipelines.get());
		assertEquals(1, dispatches.get());
		assertTrue(artifact.mesh().isPresent());
		PackProgramArtifact.MeshMetadata metadata = artifact.mesh().orElseThrow();
		assertEquals(16, metadata.payloadBytes());
		assertEquals(256, metadata.vertexCount());
		assertEquals(512, metadata.primitiveCount());
		assertTrue(metadata.objectEntry().startsWith("mtlr_object_"));
		assertTrue(metadata.meshEntry().startsWith("mtlr_mesh_"));
		assertTrue(metadata.fragmentEntry().startsWith("mtlr_fragment_"));
		assertEquals(1, metadata.resources().size());
		assertEquals(1, metadata.outputs().size());
	}

	@Test
	void lowersLayeredGeometryToInstancedRenderMetadata() throws Exception {
		ShaderPack pack = pack("");
		PackProgramRequest request = request("composite", true);
		AtomicReference<PackPipelineDesc> captured = new AtomicReference<>();
		String vertex = "struct MtlrVertexOut {\n    float4 mtlr_position [[position]];\n};\nvertex MtlrVertexOut main_vertex() {\n    MtlrVertexOut mtlr_out{};\n    return mtlr_out;\n}\n";
		PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder(
			(stage, source) -> stage == Program.Stage.GEOMETRY
				? new CompiledShader("", new Reflection(stage.ordinal(), false, List.of(), List.of(), List.of(), List.of(), null,
					new Reflection.Geometry("Layered", "triangles", "triangle_strip", 3, 1, 3, List.of(1, 2, 3)), null))
				: stage == Program.Stage.VERTEX
					? new CompiledShader(vertex, new Reflection(stage.ordinal(), false, List.of(), List.of(), List.of(), List.of(), null))
					: compiled(stage, List.of(), List.of()),
			descriptor -> {
				captured.set(descriptor);
				return 19L;
			});

		PackProgramPipelineBuilder.Result result = builder.build(request, pack.targetPlan(), pack.properties(), PassKind.COMPOSITE);
		PackProgramArtifact artifact = result.artifact().orElseThrow();
		PackPipelineDesc.LayerInstancing layers = artifact.layerInstancing().orElseThrow();
		assertEquals(3, layers.instanceCount());
		assertEquals(List.of(1, 2, 3), layers.layerMapping());
		assertEquals(layers, captured.get().layerInstancing().orElseThrow());
		assertTrue(artifact.mesh().isEmpty());
		assertFalse(artifact.translatedSources().containsKey(Program.Stage.GEOMETRY));
		assertTrue(artifact.translatedSources().get(Program.Stage.VERTEX).contains("render_target_array_index"));
	}

	@Test
	void rejectsEmptyMeshAsComputeCandidate() throws Exception {
		ShaderPack pack = pack("");
		PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder(
			(stage, source) -> stage == Program.Stage.GEOMETRY ? meshCompiled(stage, 0, 1) : compiled(stage, List.of(), List.of()),
			descriptor -> 9L, mesh -> 9L, dispatch -> true);
		PackProgramPipelineBuilder.Result result = builder.build(request("composite", true), pack.targetPlan(), pack.properties(), PassKind.COMPOSITE);
		assertEquals(Program.Stage.COMPUTE, result.failure().orElseThrow().stage());
	}

	@Test
	void rejectsOverBudgetMeshAsComputeCandidate() throws Exception {
		ShaderPack pack = pack("");
		PackProgramPipelineBuilder builder = new PackProgramPipelineBuilder(
			(stage, source) -> stage == Program.Stage.GEOMETRY ? meshCompiled(stage, 257, 1) : compiled(stage, List.of(), List.of()),
			descriptor -> 9L, mesh -> 9L, dispatch -> true);
		PackProgramPipelineBuilder.Result result = builder.build(request("composite", true), pack.targetPlan(), pack.properties(), PassKind.COMPOSITE);
		assertEquals(Program.Stage.COMPUTE, result.failure().orElseThrow().stage());
	}

	@Test
	void rejectsComputeSourcesBeforeAnyPipelineCanBeCreated() {
		Map<Program.Stage, String> sources = new EnumMap<>(Program.Stage.class);
		sources.put(Program.Stage.VERTEX, "vertex");
		sources.put(Program.Stage.FRAGMENT, "fragment");
		sources.put(Program.Stage.COMPUTE, "compute");
		assertThrows(IllegalArgumentException.class, () -> new PackProgramRequest("test-pack", "world0", "composite", sources));
	}

	private static CompiledShader compiled(Program.Stage stage, List<Reflection.Resource> resources,
		List<Reflection.FragmentOutput> outputs) {
		Reflection reflection = new Reflection(stage.ordinal(), false, resources, List.of(), List.of(), outputs, null);
		return new CompiledShader(stage == Program.Stage.VERTEX ? "vertex-msl" : "fragment-msl", reflection);
	}

	private static CompiledShader meshCompiled(Program.Stage stage, int vertices, int primitives) {
		Reflection reflection = new Reflection(stage.ordinal(), false, List.of(), List.of(), List.of(), List.of(), null,
			new Reflection.Geometry("mesh", "triangles", "triangles", vertices, primitives), null);
		return new CompiledShader("#include <metal_stdlib>\n[[object]] void object() {}\n[[mesh]] void mesh() {}", reflection);
	}

	private static PackProgramRequest request(String program, boolean deferredStages) {
		Map<Program.Stage, String> sources = new EnumMap<>(Program.Stage.class);
		sources.put(Program.Stage.VERTEX, "vertex");
		sources.put(Program.Stage.FRAGMENT, "fragment");
		if (deferredStages) {
			sources.put(Program.Stage.GEOMETRY, "geometry");
			sources.put(Program.Stage.TESS_CONTROL, "tess-control");
			sources.put(Program.Stage.TESS_EVALUATION, "tess-evaluation");
		}
		return new PackProgramRequest("test-pack", "world0", program, sources);
	}

	private static ShaderPack pack(String fragment) throws IOException {
		Path path = Files.createTempFile("mtlrender-builder", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
			entry(output, "shaders/shaders.properties", "blend.composite = SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA\n"
				+ "blend.composite.colortex0 = ONE ZERO ONE ZERO\n");
			entry(output, "shaders/composite.vsh", "vertex\n");
			entry(output, "shaders/composite.fsh", fragment + "/* DRAWBUFFERS:0 */\nfragment\n");
		}
		return ShaderPack.load(path, Map.of());
	}

	private static void entry(ZipOutputStream output, String name, String value) throws IOException {
		output.putNextEntry(new ZipEntry(name));
		output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		output.closeEntry();
	}

	private static final class SetOfStages {
		private SetOfStages() {
		}

		private static java.util.Set<Program.Stage> render() {
			return java.util.Set.of(Program.Stage.VERTEX, Program.Stage.FRAGMENT);
		}
	}
}
