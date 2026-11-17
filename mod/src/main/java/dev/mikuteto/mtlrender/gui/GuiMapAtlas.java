package dev.mikuteto.mtlrender.gui;

import java.util.HashMap;
import java.util.Map;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;

public final class GuiMapAtlas implements AutoCloseable {
	public static final int MAP_SIZE = 128;
	public static final int DEFAULT_SLOTS = 64;
	private static final Identifier LOCATION = Identifier.withDefaultNamespace("textures/gui/mtlrender_maps");
	private static GuiMapAtlas current;
	private static final Map<Integer, Long> UPDATE_TOKENS = new HashMap<>();
	private final TextureManager textures;
	private final int columns;
	private final int rows;
	private final DynamicTexture texture;
	private final NativeImage pixels;
	private final Map<Integer, Entry> entries = new HashMap<>();
	private final GuiMapAtlasState<Integer> slots;
	private long generation;
	private boolean dirty;
	private boolean closed;

	private GuiMapAtlas(final TextureManager textures, final int slots) {
		this.textures = textures;
		int side = (int)Math.ceil(Math.sqrt(slots));
		this.columns = side;
		this.rows = side;
		this.slots = new GuiMapAtlasState<>(columns * rows);
		this.pixels = new NativeImage(side * MAP_SIZE, side * MAP_SIZE, true);
		this.texture = new DynamicTexture(() -> "MTLRender GUI map atlas", this.pixels);
		textures.register(LOCATION, this.texture);
	}

	public static synchronized void markUpdated(final int mapId) {
		UPDATE_TOKENS.merge(mapId, 1L, Long::sum);
	}

	public static synchronized long updateToken(final int mapId) {
		return UPDATE_TOKENS.getOrDefault(mapId, 0L);
	}

	public static synchronized GuiMapAtlas current() {
		TextureManager textures = Minecraft.getInstance().getTextureManager();
		if (current == null || current.textures != textures) {
			if (current != null) {
				current.close();
			}
			current = new GuiMapAtlas(textures, DEFAULT_SLOTS);
		}
		return current;
	}

	public synchronized Slot prepare(final int mapId, final byte[] colors, final long version, final long resourceGeneration) {
		if (colors.length != MAP_SIZE * MAP_SIZE) {
			throw new IllegalArgumentException("map colors must contain 16384 entries");
		}
		Entry entry = entries.get(mapId);
		GuiMapAtlasState.Lookup<Integer> lookup = slots.acquire(mapId);
		if (lookup.evicted() != null) {
			entries.remove(lookup.evicted());
		}
		if (entry == null || entry.resourceGeneration != resourceGeneration || lookup.allocated()) {
			entry = new Entry(lookup.slot(), Long.MIN_VALUE, resourceGeneration);
			entries.put(mapId, entry);
		}
		if (entry.version != version) {
			write(entry.slot, colors);
			entry.version = version;
			generation++;
			dirty = true;
		}
		return slot(entry.slot);
	}

	public synchronized void flush() {
		if (dirty) {
			texture.upload();
			dirty = false;
		}
	}

	public synchronized int residentCount() {
		return entries.size();
	}

	public synchronized long generation() {
		return generation;
	}

	public static long version(final Object data, final byte[] colors, final long updateToken) {
		long hash = 0xcbf29ce484222325L ^ System.identityHashCode(data) ^ updateToken;
		for (byte color : colors) {
			hash ^= Byte.toUnsignedInt(color);
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	public static synchronized void flushCurrent() {
		if (current != null) {
			current.flush();
		}
	}

	public static synchronized void invalidate() {
		if (current != null) {
			current.close();
			current = null;
		}
		UPDATE_TOKENS.clear();
	}

	private void write(final int slot, final byte[] colors) {
		int x0 = slot % columns * MAP_SIZE;
		int y0 = slot / columns * MAP_SIZE;
		for (int y = 0; y < MAP_SIZE; y++) {
			for (int x = 0; x < MAP_SIZE; x++) {
				pixels.setPixel(x0 + x, y0 + y, MapColor.getColorFromPackedId(colors[x + y * MAP_SIZE]));
			}
		}
	}

	private Slot slot(final int index) {
		float u0 = (float)(index % columns * MAP_SIZE) / pixels.getWidth();
		float v0 = (float)(index / columns * MAP_SIZE) / pixels.getHeight();
		float u1 = (float)((index % columns + 1) * MAP_SIZE) / pixels.getWidth();
		float v1 = (float)((index / columns + 1) * MAP_SIZE) / pixels.getHeight();
		return new Slot(LOCATION, u0, u1, v0, v1);
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		texture.close();
		entries.clear();
		dirty = false;
	}

	public record Slot(Identifier texture, float u0, float u1, float v0, float v1) {
	}

	private static final class Entry {
		private final int slot;
		private long version;
		private final long resourceGeneration;

		private Entry(final int slot, final long version, final long resourceGeneration) {
			this.slot = slot;
			this.version = version;
			this.resourceGeneration = resourceGeneration;
		}
	}
}
