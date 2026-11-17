package dev.mikuteto.mtlrender.sys;

import java.util.List;

import com.mojang.logging.LogUtils;
import dev.mikuteto.mtlrender.tune.Hardware;
import org.slf4j.Logger;

public final class GameMode {
	public static final String DEFAULT_REQUEST = "auto";
	private static final Logger LOGGER = LogUtils.getLogger();
	private static Snapshot current = Snapshot.initial();

	private GameMode() {
	}

	public enum Request {
		AUTO("auto"),
		ON("on"),
		OFF("off");

		private final String value;

		Request(String value) {
			this.value = value;
		}

		public String configValue() {
			return value;
		}

		public static Request parse(String value) {
			if (value == null) {
				return OFF;
			}
			for (Request request : values()) {
				if (request.value.equals(value)) {
					return request;
				}
			}
			return OFF;
		}
	}

	public enum Status {
		DISABLED,
		ENABLED,
		UNVERIFIED_BUILD,
		UNAVAILABLE_PATH,
		FAILED_REQUEST
	}

	public enum Result {
		DISABLED,
		ENABLED,
		UNVERIFIED_BUILD,
		UNAVAILABLE_PATH,
		FAILED_REQUEST
	}

	public record Snapshot(Request request, boolean buildVerified, String selectedPath, Result result, String failureReason) {
		private static Snapshot initial() {
			return new Snapshot(Request.AUTO, false, "none", Result.UNVERIFIED_BUILD, "startup policy not initialized");
		}
	}

	public static synchronized Snapshot initialize(Request request, Hardware hardware) {
		Request selected = request == null ? Request.OFF : request;
		String build = hardware == null ? null : hardware.osBuild();
		boolean verified = PrivateGameMode.isBuildAllowListed(build);
		if (current.request() == selected && current.buildVerified() == verified && !"startup policy not initialized".equals(current.failureReason())) {
			return current;
		}
		Result result;
		try {
			result = PrivateGameMode.request(selected, hardware);
		} catch (Throwable failure) {
			result = Result.FAILED_REQUEST;
		}
		String path = path(result);
		String reason = reason(selected, result);
		current = new Snapshot(selected, verified, path, result, reason);
		warn(selected, result, reason);
		return current;
	}

	public static Snapshot snapshot() {
		return current;
	}

	public static List<String> diagnosticLines() {
		Snapshot snapshot = current;
		return List.of(
			"game_mode_request: " + snapshot.request().configValue(),
			"game_mode_build_verified: " + snapshot.buildVerified(),
			"game_mode_selected_path: " + snapshot.selectedPath(),
			"game_mode_status: " + statusText(snapshot.result()),
			"game_mode_failure_reason: " + snapshot.failureReason()
		);
	}

	private static String statusText(Result result) {
		return switch (result) {
			case DISABLED -> "disabled";
			case ENABLED -> "enabled";
			case UNVERIFIED_BUILD -> "not_verified";
			case UNAVAILABLE_PATH -> "unavailable";
			case FAILED_REQUEST -> "failed";
		};
	}

	private static String path(Result result) {
		return switch (result) {
			case ENABLED -> "direct";
			case UNAVAILABLE_PATH -> "unavailable";
			default -> "none";
		};
	}

	private static String reason(Request request, Result result) {
		return switch (result) {
			case DISABLED -> "disabled by config";
			case ENABLED -> "none";
			case UNVERIFIED_BUILD -> request == Request.AUTO ? "macOS build is not allow-listed" : "macOS build is not verified for Game Mode";
			case UNAVAILABLE_PATH -> "approved Game Mode path is unavailable";
			case FAILED_REQUEST -> "Game Mode request failed";
		};
	}

	private static void warn(Request request, Result result, String reason) {
		if (result == Result.DISABLED || result == Result.ENABLED) {
			return;
		}
		if (result == Result.UNVERIFIED_BUILD) {
			if (request == Request.AUTO) {
				LOGGER.warn("MTLRender Game Mode is off: {}; set mtlrender.gameMode=on to opt in", reason);
			} else {
				LOGGER.warn("MTLRender Game Mode opt-in was not enabled: {}; continuing without Game Mode", reason);
			}
			return;
		}
		LOGGER.warn("MTLRender Game Mode request was not enabled: {}; continuing without Game Mode", reason);
	}

	public static boolean shouldAttempt(Request request, boolean buildAllowListed) {
		return request == Request.ON || request == Request.AUTO && buildAllowListed;
	}

	public static Status evaluate(Request request, boolean buildAllowListed, boolean pathAvailable, boolean requestSucceeded) {
		if (request == Request.OFF) {
			return Status.DISABLED;
		}
		if (!buildAllowListed) {
			return Status.UNVERIFIED_BUILD;
		}
		if (!pathAvailable) {
			return Status.UNAVAILABLE_PATH;
		}
		return requestSucceeded ? Status.ENABLED : Status.FAILED_REQUEST;
	}

	public static Result result(Status status) {
		return switch (status) {
			case DISABLED -> Result.DISABLED;
			case ENABLED -> Result.ENABLED;
			case UNVERIFIED_BUILD -> Result.UNVERIFIED_BUILD;
			case UNAVAILABLE_PATH -> Result.UNAVAILABLE_PATH;
			case FAILED_REQUEST -> Result.FAILED_REQUEST;
		};
	}
}
