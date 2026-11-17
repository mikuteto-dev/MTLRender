package dev.mikuteto.mtlrender.lod;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.mikuteto.mtlrender.ffi.Mtlr;
import dev.mikuteto.mtlrender.ffi.MtlrLodMeshResult;
import dev.mikuteto.mtlrender.sys.Qos;

public final class LodMeshGenerator implements AutoCloseable {
	public static final int OUTPUT_MAGIC = 0x4c4d_5331;
	public static final int OUTPUT_VERSION = 1;
	public static final int OUTPUT_HEADER_BYTES = 64;
	public static final int QUAD_BYTES = 40;
	public static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
	public static final int DEFAULT_WORKERS = 2;
	public static final int DEFAULT_QUEUE_CAPACITY = 8;
	public static final long DEFAULT_IN_FLIGHT_BYTES = 64L * 1024L * 1024L;

	@FunctionalInterface
	public interface NativeMesher {
		NativeResult mesh(ByteBuffer input, ByteBuffer output);
	}

	public record NativeResult(int magic, int version, int status, long requiredBytes, long writtenBytes) {
	}

	private final ThreadPoolExecutor executor;
	private final NativeMesher nativeMesher;
	private final ByteBudget byteBudget;
	private final AtomicLong newestGeneration = new AtomicLong(Long.MIN_VALUE);
	private final AtomicInteger threadIds = new AtomicInteger();

	public LodMeshGenerator() {
		this(DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY, DEFAULT_IN_FLIGHT_BYTES, LodMeshGenerator::nativeMesh);
	}

	LodMeshGenerator(int workers, int queueCapacity, long inFlightBytes, NativeMesher nativeMesher) {
		if (workers < 1 || queueCapacity < 1 || inFlightBytes < MAX_OUTPUT_BYTES) {
			throw new IllegalArgumentException("invalid LOD mesh limits");
		}
		this.nativeMesher = Objects.requireNonNull(nativeMesher, "nativeMesher");
		this.byteBudget = new ByteBudget(inFlightBytes);
		ThreadFactory factory = task -> {
			Thread thread = new Thread(() -> {
				Qos.setUtilityThread();
				task.run();
			}, "MTLRender-LOD-" + threadIds.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
		this.executor = new ThreadPoolExecutor(
			workers, workers, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
	}

	public CompletableFuture<LodMesh> request(LodData.LodSection section, LodMesh.Material[] materials) {
		return request(section, materials, 0L);
	}

	public CompletableFuture<LodMesh> request(LodData.LodSection section, LodMesh.Material[] materials, long generation) {
		Objects.requireNonNull(section, "section");
		Objects.requireNonNull(materials, "materials");
		CompletableFuture<LodMesh> future = new CompletableFuture<>();
		if (generation < newestGeneration.get()) {
			future.completeExceptionally(new StaleRequestException());
			return future;
		}
		long reserved;
		try {
			reserved = Math.addExact(section.estimatedBytes(), Math.multiplyExact((long) materials.length, LodMesh.MATERIAL_BYTES));
			reserved = Math.addExact(reserved, MAX_OUTPUT_BYTES);
		} catch (ArithmeticException error) {
			future.completeExceptionally(new IllegalArgumentException("LOD request is too large", error));
			return future;
		}
		if (!byteBudget.reserve(reserved)) {
			future.completeExceptionally(new RejectedExecutionException("LOD byte budget is full"));
			return future;
		}
		Task task = new Task(section, materials.clone(), generation, future, reserved);
		try {
			executor.execute(task);
		} catch (RejectedExecutionException error) {
			byteBudget.release(reserved);
			future.completeExceptionally(error);
		}
		return future;
	}

	public void invalidateBefore(long generation) {
		newestGeneration.accumulateAndGet(generation, Math::max);
	}

	public long inFlightBytes() {
		return byteBudget.used();
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

	private final class Task implements Runnable {
		private final LodData.LodSection section;
		private final LodMesh.Material[] materials;
		private final long generation;
		private final CompletableFuture<LodMesh> future;
		private final long reserved;

		private Task(LodData.LodSection section, LodMesh.Material[] materials, long generation,
			CompletableFuture<LodMesh> future, long reserved) {
			this.section = section;
			this.materials = materials;
			this.generation = generation;
			this.future = future;
			this.reserved = reserved;
		}

		@Override
		public void run() {
			try {
				if (future.isCancelled() || generation < newestGeneration.get()) {
					return;
				}
				ByteBuffer input = LodMesh.encode(new LodMesh.Input(section, materials, true));
				if (input.remaining() > MAX_OUTPUT_BYTES * 2L || future.isCancelled()) {
					if (!future.isCancelled()) {
						future.completeExceptionally(new IllegalArgumentException("LOD input is too large"));
					}
					return;
				}
				ByteBuffer output = ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
				NativeResult result = nativeMesher.mesh(input, output);
				if (future.isCancelled() || generation < newestGeneration.get()) {
					return;
				}
				future.complete(validate(section.key(), result, output));
			} catch (Throwable error) {
				if (!future.isCancelled()) {
					future.completeExceptionally(error);
				}
			} finally {
				byteBudget.release(reserved);
			}
		}
	}

	private static LodMesh validate(LodData.LodKey key, NativeResult result, ByteBuffer output) {
		Objects.requireNonNull(result, "native result");
		if (result.magic() != OUTPUT_MAGIC || result.version() != OUTPUT_VERSION || result.status() != 0) {
			throw new IllegalStateException("invalid LOD native result");
		}
		long written = result.writtenBytes();
		if (result.requiredBytes() != written || written < OUTPUT_HEADER_BYTES || written > output.capacity()) {
			throw new IllegalStateException("invalid LOD native byte length");
		}
		if (written > Integer.MAX_VALUE) {
			throw new IllegalStateException("LOD native result is too large");
		}
		ByteBuffer bytes = output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		bytes.position(0).limit((int) written);
		if (bytes.getInt(0) != OUTPUT_MAGIC || bytes.getInt(4) != OUTPUT_VERSION
			|| bytes.getInt(8) != key.level() || bytes.getInt(16) != key.originX()
			|| bytes.getInt(20) != key.originY() || bytes.getInt(24) != key.originZ()
			|| Integer.toUnsignedLong(bytes.getInt(12)) != QUAD_BYTES
			|| Integer.toUnsignedLong(bytes.getInt(56)) != written) {
			throw new IllegalStateException("invalid LOD native output header");
		}
		int[] buckets = new int[7];
		long quadBytes = 0;
		for (int index = 0; index < buckets.length; index++) {
			int count = bytes.getInt(28 + index * 4);
			long unsigned = Integer.toUnsignedLong(count);
			if (unsigned > Integer.MAX_VALUE) {
				throw new IllegalStateException("invalid LOD bucket count");
			}
			buckets[index] = (int) unsigned;
			quadBytes = Math.addExact(quadBytes, Math.multiplyExact(unsigned, QUAD_BYTES));
		}
		if (quadBytes != written - OUTPUT_HEADER_BYTES) {
			throw new IllegalStateException("invalid LOD bucket byte length");
		}
		byte[] immutable = new byte[(int) written];
		bytes.get(immutable);
		return LodMesh.nativeResult(key, (int) quadBytes, buckets, OUTPUT_VERSION, immutable);
	}

	private static NativeResult nativeMesh(ByteBuffer input, ByteBuffer output) {
		if (!input.isDirect() || !output.isDirect() || input.position() < 0 || input.limit() > input.capacity()
			|| output.position() < 0 || output.limit() > output.capacity()) {
			throw new IllegalArgumentException("native LOD buffers must be direct and bounded");
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment inputSegment = MemorySegment.ofBuffer(input);
			MemorySegment outputSegment = MemorySegment.ofBuffer(output);
			MemorySegment result = Mtlr.mtlr_lod_mesh(
				arena, inputSegment, input.remaining(), outputSegment, output.capacity());
			return new NativeResult(
				MtlrLodMeshResult.magic(result), MtlrLodMeshResult.version(result),
				MtlrLodMeshResult.status(result), MtlrLodMeshResult.required_bytes(result),
				MtlrLodMeshResult.written_bytes(result));
		} catch (LinkageError error) {
			throw new IllegalStateException("LOD native mesher is unavailable", error);
		}
	}

	private static final class ByteBudget {
		private final long limit;
		private long used;

		private ByteBudget(long limit) {
			this.limit = limit;
		}

		private synchronized boolean reserve(long bytes) {
			if (bytes > limit - used) {
				return false;
			}
			used += bytes;
			return true;
		}

		private synchronized void release(long bytes) {
			used -= bytes;
		}

		private synchronized long used() {
			return used;
		}
	}

	public static final class StaleRequestException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
