package dev.mikuteto.mtlrender.pack.runtime;

import java.util.List;

public enum PassKind {
	SETUP("setup"),
	BEGIN("begin"),
	SHADOWCOMP("shadowcomp"),
	PREPARE("prepare"),
	DEFERRED("deferred"),
	COMPOSITE("composite"),
	FINAL("final");

	private static final List<PassKind> ORDER = List.of(values());

	private final String sourceName;

	PassKind(String sourceName) {
		this.sourceName = sourceName;
	}

	public String sourceName() {
		return sourceName;
	}

	public int order() {
		return ordinal();
	}

	public static List<PassKind> executionOrder() {
		return ORDER;
	}
}
