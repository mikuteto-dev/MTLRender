package dev.mikuteto.mtlrender.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

final class LodMeshGeneratorTest {
	private static LodData.LodSection section() {
		return new LodData.LodSection(
			new LodData.LodKey("overworld", 0, 0, 0, 0),
			new int[LodData.LOD_VOXELS],
			LodData.Heightmap.lod(new int[LodData.LOD_SIZE * LodData.LOD_SIZE])
		);
	}

	private static LodMeshGenerator.NativeMesher valid(AtomicReference<ByteBuffer> seen) {
		return (input, output) -> {
			seen.set(input);
			ByteBuffer bytes = output.order(ByteOrder.LITTLE_ENDIAN);
			bytes.putInt(0, LodMeshGenerator.OUTPUT_MAGIC);
			bytes.putInt(4, LodMeshGenerator.OUTPUT_VERSION);
			bytes.putInt(8, 0);
			bytes.putInt(12, LodMeshGenerator.QUAD_BYTES);
			bytes.putInt(16, 0);
			bytes.putInt(20, 0);
			bytes.putInt(24, 0);
			bytes.putInt(56, LodMeshGenerator.OUTPUT_HEADER_BYTES);
			return new LodMeshGenerator.NativeResult(
				LodMeshGenerator.OUTPUT_MAGIC, LodMeshGenerator.OUTPUT_VERSION, 0,
				LodMeshGenerator.OUTPUT_HEADER_BYTES, LodMeshGenerator.OUTPUT_HEADER_BYTES);
		};
	}

	@Test
	void encodesPayloadOnUtilityWorkerAndPublishesImmutableBytes() throws Exception {
		AtomicReference<ByteBuffer> seen = new AtomicReference<>();
		AtomicBoolean utility = new AtomicBoolean();
		try (LodMeshGenerator generator = new LodMeshGenerator(1, 1, 32L * 1024L * 1024L, (input, output) -> {
			utility.set(Thread.currentThread().getName().startsWith("MTLRender-LOD-"));
			return valid(seen).mesh(input, output);
		})) {
			LodMesh mesh = generator.request(section(), new LodMesh.Material[] { new LodMesh.Material(1, 2, 0, 0, 0, 0) }).get(5, TimeUnit.SECONDS);
			assertTrue(utility.get());
			assertEquals(LodMeshGenerator.OUTPUT_HEADER_BYTES, mesh.bytes().length);
			byte[] copy = mesh.bytes();
			copy[0] = 0;
			assertEquals(LodMeshGenerator.OUTPUT_MAGIC, ByteBuffer.wrap(mesh.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt());
			assertEquals(LodMesh.INPUT_MAGIC, seen.get().order(ByteOrder.LITTLE_ENDIAN).getInt(0));
		}
	}

	@Test
	void malformedNativeResultFailsWithoutPublishing() {
		try (LodMeshGenerator generator = new LodMeshGenerator(1, 1, 32L * 1024L * 1024L,
			(input, output) -> new LodMeshGenerator.NativeResult(0, 1, 0, 64, 64))) {
			CompletableFuture<LodMesh> future = generator.request(section(), new LodMesh.Material[0]);
			assertThrows(Exception.class, future::join);
		}
	}

	@Test
	void cancellationSkipsNativeCall() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean called = new AtomicBoolean();
		try (LodMeshGenerator generator = new LodMeshGenerator(1, 1, 48L * 1024L * 1024L, (input, output) -> {
			called.set(true);
			started.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
			return valid(new AtomicReference<>()).mesh(input, output);
		})) {
			CompletableFuture<LodMesh> first = generator.request(section(), new LodMesh.Material[0]);
			assertTrue(started.await(5, TimeUnit.SECONDS));
			CompletableFuture<LodMesh> second = generator.request(section(), new LodMesh.Material[0]);
			assertTrue(second.cancel(false));
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
			assertThrows(CancellationException.class, second::join);
			assertTrue(called.get());
		}
	}

	@Test
	void staleRequestsAreRejected() {
		try (LodMeshGenerator generator = new LodMeshGenerator(1, 1, 32L * 1024L * 1024L, valid(new AtomicReference<>()))) {
			generator.invalidateBefore(3);
			CompletableFuture<LodMesh> future = generator.request(section(), new LodMesh.Material[0], 2);
			assertThrows(Exception.class, future::join);
		}
	}
}
