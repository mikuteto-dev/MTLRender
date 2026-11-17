package dev.mikuteto.mtlrender.terrain;

import java.util.HashMap;
import java.util.Map;

public final class MeshOwnership {
	private static final Map<Long, Long> GENERATIONS = new HashMap<>();
	private static final ThreadLocal<Token> CONTEXT = new ThreadLocal<>();

	private MeshOwnership() {
	}

	public static synchronized Token begin(long section) {
		long generation = GENERATIONS.getOrDefault(section, 0L) + 1L;
		GENERATIONS.put(section, generation);
		return new Token(section, generation);
	}

	public static synchronized boolean current(Token token) {
		return token != null && GENERATIONS.getOrDefault(token.section(), -1L) == token.generation();
	}

	public static synchronized void invalidate(Token token) {
		if (current(token)) {
			GENERATIONS.remove(token.section());
		}
	}

	public static synchronized void reset() {
		GENERATIONS.clear();
		CONTEXT.remove();
	}

	public static void enter(Token token) {
		if (token == null) {
			CONTEXT.remove();
		} else {
			CONTEXT.set(token);
		}
	}

	public static void leave() {
		CONTEXT.remove();
	}

	public static Token context() {
		return CONTEXT.get();
	}

	public record Token(long section, long generation) {
	}

	public static final class Lease {
		private final Token token;
		private int callbacks;
		private boolean releaseRequested;
		private boolean released;
		private boolean cancelled;

		public Lease(Token token, int callbacks) {
			if (callbacks < 0) {
				throw new IllegalArgumentException("callbacks must be non-negative");
			}
			this.token = token;
			this.callbacks = callbacks;
		}

		public synchronized boolean acceptCallback(boolean sortedIndexBuffer) {
			if (cancelled || token != null && !MeshOwnership.current(token)) {
				return false;
			}
			if (sortedIndexBuffer) {
				return true;
			}
			if (callbacks == 0) {
				return false;
			}
			callbacks--;
			return true;
		}

		public synchronized boolean uploadComplete() {
			return callbacks == 0;
		}

		public synchronized boolean requestRelease() {
			releaseRequested = true;
			return takeRelease();
		}

		public synchronized boolean cancel() {
			if (cancelled) {
				return false;
			}
			cancelled = true;
			MeshOwnership.invalidate(token);
			return true;
		}

		public synchronized boolean takeRelease() {
			if (!released && releaseRequested && callbacks == 0) {
				released = true;
				return true;
			}
			return false;
		}

		public synchronized Token token() {
			return token;
		}
	}
}
