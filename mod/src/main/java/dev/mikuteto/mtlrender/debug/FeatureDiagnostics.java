package dev.mikuteto.mtlrender.debug;

public final class FeatureDiagnostics {
	public record Snapshot(
		long candidates,
		long cpuRejected,
		long maskRejected,
		long forcedVisible,
		long prepared,
		long executed,
		boolean maskRequested,
		boolean maskResultReady,
		boolean failOpen
	) {
		public static Snapshot empty() {
			return new Snapshot(0L, 0L, 0L, 0L, 0L, 0L, false, false, false);
		}
	}

	private static volatile Snapshot current = Snapshot.empty();

	private FeatureDiagnostics() {
	}

	public static Snapshot snapshot() {
		return current;
	}

	public static void publish(Snapshot snapshot) {
		current = snapshot;
	}

	public static String line() {
		Snapshot value = current;
		return String.format(
			java.util.Locale.ROOT,
			"Features: candidates %d cpu-rejected %d mask-rejected %d forced %d prepared %d executed %d mask %s result %s fail-open %s",
			value.candidates(), value.cpuRejected(), value.maskRejected(), value.forcedVisible(), value.prepared(), value.executed(),
			value.maskRequested() ? "requested" : "none", value.maskResultReady() ? "ready" : "none", value.failOpen() ? "yes" : "no"
		);
	}
}
