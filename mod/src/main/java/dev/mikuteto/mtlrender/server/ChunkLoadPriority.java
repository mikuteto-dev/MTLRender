package dev.mikuteto.mtlrender.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.world.level.ChunkPos;

public final class ChunkLoadPriority {
	private static final int NO_PLAYER = Integer.MAX_VALUE;
	private static final int MAX_SCORE = Integer.MAX_VALUE - 1;
	private static final ChunkLoadPriority SHARED = new ChunkLoadPriority();
	private final ConcurrentMap<Long, ChunkPos> players = new ConcurrentHashMap<>();

	public static ChunkLoadPriority shared() {
		return SHARED;
	}

	public void register(long playerId, ChunkPos pos) {
		players.put(playerId, pos);
	}

	public void remove(long playerId) {
		players.remove(playerId);
	}

	public void clear() {
		players.clear();
	}

	public int score(ChunkPos pos) {
		long best = Long.MAX_VALUE;
		for (ChunkPos player : players.values()) {
			long dx = (long) pos.x() - player.x();
			long dz = (long) pos.z() - player.z();
			long distance = squaredDistance(dx, dz);
			if (distance < best) {
				best = distance;
			}
		}
		if (best == Long.MAX_VALUE) {
			return NO_PLAYER;
		}
		return best >= MAX_SCORE ? MAX_SCORE : (int) best;
	}

	private static long squaredDistance(long dx, long dz) {
		long limit = MAX_SCORE;
		if (Math.abs(dx) > 46_340L || Math.abs(dz) > 46_340L) {
			return limit;
		}
		long distance = dx * dx + dz * dz;
		return distance < 0L || distance >= limit ? limit : distance;
	}
}
