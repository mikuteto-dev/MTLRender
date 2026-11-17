package dev.mikuteto.mtlrender.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AudioMemoryTest {
	@Test
	void completeBufferCountsDecodedBytesBeforeUpload() {
		AudioMemory.Snapshot snapshot = AudioMemory.measure(List.of(new AudioMemory.BufferState(true, true, false, 128)));
		assertEquals(1L, snapshot.cachedSoundCount());
		assertEquals(128L, snapshot.decodedDirectBytes());
		assertEquals(0L, snapshot.alBufferCount());
	}

	@Test
	void completeBufferCountsOpenAlBytesAfterUpload() {
		AudioMemory.Snapshot snapshot = AudioMemory.measure(List.of(new AudioMemory.BufferState(true, false, true, 256)));
		assertEquals(0L, snapshot.decodedDirectBytes());
		assertEquals(1L, snapshot.alBufferCount());
		assertEquals(256L, snapshot.alBufferBytes());
	}

	@Test
	void streamingBuffersAreNotSoundCacheEntries() {
		AudioMemory.Snapshot snapshot = AudioMemory.measure(List.of(new AudioMemory.BufferState(false, false, false, 16384)));
		assertEquals(AudioMemory.Snapshot.empty(), snapshot);
	}

	@Test
	void clearAndFailedLoadsAreExcluded() {
		AudioMemory.Snapshot snapshot = AudioMemory.measure(List.of(
			new AudioMemory.BufferState(false, false, false, 0),
			new AudioMemory.BufferState(true, false, false, 64)
		));
		assertEquals(1L, snapshot.cachedSoundCount());
		assertEquals(0L, snapshot.alBufferCount());
		assertEquals(0L, snapshot.decodedDirectBytes());
	}
}
