package dev.mikuteto.mtlrender.sys;

import java.util.Set;

import dev.mikuteto.mtlrender.tune.Hardware;

public final class PrivateGameMode {
	private static final Set<String> TESTED_BUILDS = Set.of();

	private PrivateGameMode() {
	}

	public static GameMode.Result request(GameMode.Request request, Hardware hardware) {
		try {
			return request(request, hardware == null ? null : hardware.osBuild());
		} catch (Throwable ignored) {
			return GameMode.Result.FAILED_REQUEST;
		}
	}

	public static GameMode.Result request(GameMode.Request request, String osBuild) {
		try {
			if (request == GameMode.Request.OFF) {
				return GameMode.Result.DISABLED;
			}
			if (!isBuildAllowListed(osBuild)) {
				return GameMode.Result.UNVERIFIED_BUILD;
			}
			return GameMode.Result.UNAVAILABLE_PATH;
		} catch (Throwable ignored) {
			return GameMode.Result.FAILED_REQUEST;
		}
	}

	public static boolean isBuildAllowListed(String osBuild) {
		return osBuild != null && TESTED_BUILDS.contains(osBuild);
	}

	public static boolean pathAvailable(boolean framework, boolean symbol, boolean selector) {
		return framework && symbol && selector;
	}

	public static GameMode.Result failed(Throwable failure) {
		return GameMode.Result.FAILED_REQUEST;
	}
}
