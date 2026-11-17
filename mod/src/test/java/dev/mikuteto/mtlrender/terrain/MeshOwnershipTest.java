package dev.mikuteto.mtlrender.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MeshOwnershipTest {
	@Test
	void uploadBeforeReleaseWaitsForCallbacks() {
		MeshOwnership.Token token = MeshOwnership.begin(1L);
		MeshOwnership.Lease lease = new MeshOwnership.Lease(token, 2);
		assertFalse(lease.requestRelease());
		assertTrue(lease.acceptCallback(false));
		assertFalse(lease.takeRelease());
		assertTrue(lease.acceptCallback(false));
		assertTrue(lease.requestRelease());
	}

	@Test
	void generationMismatchRejectsStaleCallback() {
		MeshOwnership.Token oldToken = MeshOwnership.begin(2L);
		MeshOwnership.Lease oldLease = new MeshOwnership.Lease(oldToken, 1);
		MeshOwnership.begin(2L);
		assertFalse(oldLease.acceptCallback(false));
	}
}
