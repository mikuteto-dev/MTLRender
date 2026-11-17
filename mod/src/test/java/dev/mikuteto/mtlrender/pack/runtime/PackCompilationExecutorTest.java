package dev.mikuteto.mtlrender.pack.runtime;

import dev.mikuteto.mtlrender.pack.Program;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PackCompilationExecutorTest {
	@Test
	void usesPerformanceCoreBoundAndUtilityQos() throws Exception {
		AtomicInteger current = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		AtomicInteger qosCalls = new AtomicInteger();
		CountDownLatch started = new CountDownLatch(3);
		PackCompilationExecutor executor = new PackCompilationExecutor(3, 8, Thread::new, qosCalls::incrementAndGet);
		try {
			PackCompilationExecutor.Result result = executor.compile(requests(8), request -> {
				int active = current.incrementAndGet();
				maximum.accumulateAndGet(active, Math::max);
				started.countDown();
				started.await(2, TimeUnit.SECONDS);
				current.decrementAndGet();
				return PackCompilationExecutor.Outcome.failure(failure(request, "rejected"));
			});
			assertEquals(8, result.attempted());
			assertEquals(8, result.failures().size());
			assertEquals(0, result.artifacts().size());
			assertTrue(maximum.get() <= 3);
			assertTrue(qosCalls.get() >= 1);
		} finally {
			executor.close();
		}
	}

	@Test
	void retainsEveryFailureAndNamesWorkers() {
		List<String> names = new ArrayList<>();
		ThreadFactory factory = Thread::new;
		PackCompilationExecutor executor = new PackCompilationExecutor(2, 2, factory, () -> { });
		try {
			PackCompilationExecutor.Result result = executor.compile(requests(2), request -> {
				synchronized (names) {
					names.add(Thread.currentThread().getName());
				}
				return PackCompilationExecutor.Outcome.failure(failure(request, request.program()));
			});
			assertEquals(List.of("p0", "p1"), result.failures().stream()
				.map(PackProgramFailure::normalizedFirstError).sorted().toList());
			synchronized (names) {
			assertTrue(names.stream().allMatch(name -> name.startsWith("MTLRender-PackCompile-")));
			assertTrue(names.stream().noneMatch(name -> name.contains("ForkJoinPool")));
		}
		} finally {
			executor.close();
		}
	}

	private static List<PackProgramRequest> requests(int count) {
		List<PackProgramRequest> requests = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			requests.add(new PackProgramRequest("pack", "world", "p" + index,
				java.util.Map.of(Program.Stage.VERTEX, "", Program.Stage.FRAGMENT, "")));
		}
		return requests;
	}

	private static PackProgramFailure failure(PackProgramRequest request, String message) {
		return new PackProgramFailure(request.pack(), request.dimension(), request.program(),
			Program.Stage.FRAGMENT, message);
	}
}
