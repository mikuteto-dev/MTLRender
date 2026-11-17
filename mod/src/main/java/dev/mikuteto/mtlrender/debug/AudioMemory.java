package dev.mikuteto.mtlrender.debug;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import com.mojang.blaze3d.audio.SoundBuffer;
import dev.mikuteto.mtlrender.mixin.SoundBufferAccess;
import dev.mikuteto.mtlrender.mixin.SoundBufferLibraryAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;

public final class AudioMemory {
	private static final AudioMemory GLOBAL = new AudioMemory();
	private static final MemoryDiagnostics.Registration REGISTRATION = MemoryDiagnostics.global().register("java.audio", GLOBAL::memorySource);

	private volatile SoundBufferLibrary library;

	public static AudioMemory global() {
		return GLOBAL;
	}

	public void attach(SoundBufferLibrary value) {
		library = value;
	}

	public Snapshot snapshot() {
		SoundBufferLibrary current = library;
		if (current == null) {
			return Snapshot.empty();
		}
		MapView view = new MapView(((SoundBufferLibraryAccess) (Object) current).mtlrender$cache());
		return measure(view.states());
	}

	public MemorySource.Values memorySource() {
		Snapshot value = snapshot();
		return new MemorySource.Values(OptionalLong.of(value.alBufferBytes()), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
	}

	public static Snapshot measure(Collection<BufferState> states) {
		long decoded = 0L;
		long alCount = 0L;
		long alBytes = 0L;
		long cached = 0L;
		for (BufferState state : states) {
			if (!state.completed()) {
				continue;
			}
			cached++;
			if (state.directData()) {
				decoded = Math.addExact(decoded, state.size());
			}
			if (state.hasAlBuffer()) {
				alCount++;
				alBytes = Math.addExact(alBytes, state.size());
			}
		}
		return new Snapshot(cached, decoded, alCount, alBytes);
	}

	public record BufferState(boolean completed, boolean directData, boolean hasAlBuffer, long size) {
		public BufferState {
			if (size < 0L) {
				throw new IllegalArgumentException("audio size must be non-negative");
			}
		}
	}

	public record Snapshot(long cachedSoundCount, long decodedDirectBytes, long alBufferCount, long alBufferBytes) {
		public Snapshot {
			if (cachedSoundCount < 0L || decodedDirectBytes < 0L || alBufferCount < 0L || alBufferBytes < 0L) {
				throw new IllegalArgumentException("audio values must be non-negative");
			}
		}

		public static Snapshot empty() {
			return new Snapshot(0L, 0L, 0L, 0L);
		}
	}

	private static final class MapView {
		private final Collection<CompletableFuture<SoundBuffer>> futures;

		private MapView(java.util.Map<?, CompletableFuture<SoundBuffer>> cache) {
			futures = cache.values();
		}

		private Collection<BufferState> states() {
			java.util.ArrayList<BufferState> states = new java.util.ArrayList<>(futures.size());
			for (CompletableFuture<SoundBuffer> future : futures) {
				SoundBuffer buffer = future.getNow(null);
				if (buffer == null) {
					states.add(new BufferState(false, false, false, 0L));
					continue;
				}
				SoundBufferAccess access = (SoundBufferAccess) (Object) buffer;
				ByteBuffer data = access.mtlrender$data();
				states.add(new BufferState(true, data != null && data.isDirect(), access.mtlrender$hasAlBuffer(), access.mtlrender$size()));
			}
			return states;
		}
	}
}
