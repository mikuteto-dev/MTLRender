package dev.mikuteto.mtlrender.pack;

import java.io.IOException;

public final class IncludeException extends IOException {
	private final String file;
	private final int line;

	public IncludeException(String message, String file, int line) {
		super(message + locationSuffix(file, line));
		this.file = file;
		this.line = line;
	}

	public String file() {
		return file;
	}

	public int line() {
		return line;
	}

	private static String locationSuffix(String file, int line) {
		if (file == null) {
			return "";
		}
		if (line <= 0) {
			return " (" + file + ")";
		}
		return " (" + file + ":" + line + ")";
	}
}
