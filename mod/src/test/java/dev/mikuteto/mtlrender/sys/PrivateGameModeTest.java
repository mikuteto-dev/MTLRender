package dev.mikuteto.mtlrender.sys;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class PrivateGameModeTest {
	@Test
	void testedBuildAllowListIsExact() {
		assertFalse(PrivateGameMode.isBuildAllowListed("26A434"));
		assertFalse(PrivateGameMode.isBuildAllowListed("27.0.1"));
		assertFalse(PrivateGameMode.isBuildAllowListed(null));
	}

	@Test
	void unknownBuildIsRejectedBeforePrivatePath() {
		assertEquals(GameMode.Result.UNVERIFIED_BUILD, PrivateGameMode.request(GameMode.Request.AUTO, "26A434"));
		assertEquals(GameMode.Result.UNVERIFIED_BUILD, PrivateGameMode.request(GameMode.Request.ON, "26A434"));
	}

	@Test
	void missingRuntimePiecesMakePathUnavailable() {
		assertFalse(PrivateGameMode.pathAvailable(false, true, true));
		assertFalse(PrivateGameMode.pathAvailable(true, false, true));
		assertFalse(PrivateGameMode.pathAvailable(true, true, false));
		assertTrue(PrivateGameMode.pathAvailable(true, true, true));
	}

	@Test
	void disabledRequestDoesNothing() {
		assertEquals(GameMode.Result.DISABLED, PrivateGameMode.request(GameMode.Request.OFF, "26A434"));
	}

	@Test
	void failuresBecomeNonFatalResults() {
		assertDoesNotThrow(() -> PrivateGameMode.failed(new AssertionError("probe failure")));
		assertEquals(GameMode.Result.FAILED_REQUEST, PrivateGameMode.failed(new AssertionError("probe failure")));
	}
}
