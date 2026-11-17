package dev.mikuteto.mtlrender.pack.compiler;

public final class GlslCompileException extends Exception {
	public GlslCompileException(String message) {
		super(message);
	}

	public GlslCompileException(String message, Throwable cause) {
		super(message, cause);
	}
}
