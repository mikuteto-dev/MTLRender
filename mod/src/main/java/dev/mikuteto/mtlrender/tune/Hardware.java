package dev.mikuteto.mtlrender.tune;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public record Hardware(
	String chip,
	long memoryBytes,
	int performanceCores,
	int efficiencyCores,
	String osVersion,
	String osBuild,
	int gpuCores,
	NativeCaps nativeCaps,
	int mesherWorkers
) {

	public record NativeCaps(
		int appleFamily,
		boolean raytracing,
		boolean raytracingFromRender,
		long recommendedMaxWorkingSet,
		long maxBufferLength
	) {

		public static NativeCaps missing() {
			return new NativeCaps(0, false, false, 0, 0);
		}
	}

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final AtomicBoolean WORKER_COUNT_LOGGED = new AtomicBoolean();
	private static final int FALLBACK_MESHER_WORKERS = 2;
	private static final int MAX_MESHER_WORKERS = 16;
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MemorySegment SYSCTL = LINKER.defaultLookup()
		.find("sysctlbyname")
		.orElse(MemorySegment.NULL);
	private static final MethodHandle SYSCTL_CALL = SYSCTL.equals(MemorySegment.NULL) ? null
		: LINKER.downcallHandle(
			SYSCTL,
			FunctionDescriptor.of(
				ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.JAVA_LONG
			)
		);

	public static Hardware detect(NativeCaps nativeCaps) {
		int performanceCores = (int) number("hw.perflevel0.logicalcpu");
		int efficiencyCores = (int) number("hw.perflevel1.logicalcpu");
		int mesherWorkers = mesherWorkers(performanceCores, efficiencyCores);
		if (WORKER_COUNT_LOGGED.compareAndSet(false, true)) {
			LOGGER.info(
				"MTLRender terrain workers: {} (P cores: {}, E cores: {}, fallback: {})",
				mesherWorkers,
				performanceCores,
				efficiencyCores,
				performanceCores <= 0 || efficiencyCores <= 0
			);
		}
		return new Hardware(
			string("machdep.cpu.brand_string"),
			number("hw.memsize"),
			performanceCores,
			efficiencyCores,
			string("kern.osproductversion"),
			string("kern.osversion"),
			probeGpuCores(),
			nativeCaps,
			mesherWorkers
		);
	}

	public static int mesherWorkers(int performanceCores, int efficiencyCores) {
		if (performanceCores <= 0 || efficiencyCores <= 0) {
			return FALLBACK_MESHER_WORKERS;
		}
		long totalCores = (long) performanceCores + efficiencyCores;
		long workers = Math.max(1L, totalCores - 1L);
		return (int) Math.min(MAX_MESHER_WORKERS, workers);
	}

	private static String string(String name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment nameSegment = arena.allocateFrom(name);
			MemorySegment length = arena.allocate(ValueLayout.JAVA_LONG);
			if (!sysctl(nameSegment, MemorySegment.NULL, length)) {
				return "";
			}
			long size = length.get(ValueLayout.JAVA_LONG, 0);
			if (size <= 0 || size > 1024) {
				return "";
			}
			MemorySegment out = arena.allocate(size);
			if (!sysctl(nameSegment, out, length)) {
				return "";
			}
			return out.getString(0);
		} catch (Throwable e) {
			return "";
		}
	}

	private static long number(String name) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment nameSegment = arena.allocateFrom(name);
			MemorySegment length = arena.allocate(ValueLayout.JAVA_LONG);
			length.set(ValueLayout.JAVA_LONG, 0, Long.BYTES);
			MemorySegment out = arena.allocate(Long.BYTES);
			if (!sysctl(nameSegment, out, length)) {
				return 0;
			}
			return switch ((int) length.get(ValueLayout.JAVA_LONG, 0)) {
				case Integer.BYTES -> out.get(ValueLayout.JAVA_INT, 0) & 0xFFFFFFFFL;
				case Long.BYTES -> out.get(ValueLayout.JAVA_LONG, 0);
				default -> 0;
			};
		} catch (Throwable e) {
			return 0;
		}
	}

	private static boolean sysctl(MemorySegment name, MemorySegment out, MemorySegment length) {
		if (SYSCTL_CALL == null) {
			return false;
		}
		try {
			return (int) SYSCTL_CALL.invoke(name, out, length, MemorySegment.NULL, 0L) == 0;
		} catch (Throwable e) {
			return false;
		}
	}

	private static int probeGpuCores() {
		Process process = null;
		try {
			process = new ProcessBuilder("ioreg", "-rc", "AGXAccelerator", "-d1").start();
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return -1;
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (trimmed.startsWith("\"gpu-core-count\" = ")) {
					return parse(trimmed.substring("\"gpu-core-count\" = ".length()));
				}
			}
			return -1;
		} catch (IOException e) {
			return -1;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
	}

	private static int parse(String value) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
