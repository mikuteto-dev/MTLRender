package dev.mikuteto.mtlrender.backend;

import java.util.ArrayDeque;

final class FrameQueue {
	private record Entry(long frame, Runnable action) {
	}

	private final ArrayDeque<Entry> entries = new ArrayDeque<>();

	void add(long frame, Runnable action) {
		entries.addLast(new Entry(frame, action));
	}

	void runCompleted(long completedFrames) {
		while (!entries.isEmpty() && entries.peekFirst().frame() < completedFrames) {
			entries.pollFirst().action().run();
		}
	}

	void runAll() {
		while (!entries.isEmpty()) {
			entries.pollFirst().action().run();
		}
	}
}
