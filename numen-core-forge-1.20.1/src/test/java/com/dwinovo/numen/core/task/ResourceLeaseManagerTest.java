package com.dwinovo.numen.core.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLeaseManagerTest {
    private static ResourceLeaseManager.TaskKey task(String id) { return new ResourceLeaseManager.TaskKey(UUID.nameUUIDFromBytes(id.getBytes()), id); }
    private static ResourceLeaseManager.Resource.Key key(String id) { return new ResourceLeaseManager.Resource.Key("station", id, id); }

    @Test void sameResourceIsExclusiveAndReportsOwner() {
        var leases = new ResourceLeaseManager(20);
        assertTrue(leases.acquire(task("a"), "伙伴 A", List.of(key("furnace")), 0, 10).acquired());
        var blocked = leases.acquire(task("b"), "伙伴 B", List.of(key("furnace")), 1, 10);
        assertFalse(blocked.acquired());
        assertEquals("伙伴 A", blocked.blockingLease().companionName());
        assertEquals(1, blocked.blockingLease().waiters().size());
    }

    @Test void expiryAndReleaseMakeResourceReusable() {
        var leases = new ResourceLeaseManager(10);
        leases.acquire(task("a"), "A", List.of(key("chest")), 0, 10);
        assertEquals(1, leases.sweep(10));
        assertTrue(leases.acquire(task("b"), "B", List.of(key("chest")), 10, 10).acquired());
        leases.releaseCompanion(task("b").companionUuid());
        assertTrue(leases.snapshot().isEmpty());
    }

    @Test void renewalExtendsExpiry() {
        var leases = new ResourceLeaseManager(10);
        leases.acquire(task("a"), "A", List.of(key("bench")), 0, 10);
        leases.renew(task("a"), 8);
        assertEquals(0, leases.sweep(10));
        assertEquals(1, leases.sweep(18));
    }

    @Test void overlappingBlueprintRegionsConflict() {
        var leases = new ResourceLeaseManager(20);
        var first = new ResourceLeaseManager.Resource.Region("overworld", 0, 0, 0, 5, 5, 5, "first");
        var overlap = new ResourceLeaseManager.Resource.Region("overworld", 5, 2, 2, 9, 4, 4, "overlap");
        var separate = new ResourceLeaseManager.Resource.Region("overworld", 6, 0, 0, 9, 5, 5, "separate");
        assertTrue(leases.acquire(task("a"), "A", List.of(first), 0, 10).acquired());
        assertFalse(leases.acquire(task("b"), "B", List.of(overlap), 1, 10).acquired());
        assertTrue(leases.acquire(task("c"), "C", List.of(separate), 1, 10).acquired());
    }

    @Test void multiResourceAcquisitionIsAllOrNothingInStableOrder() {
        var leases = new ResourceLeaseManager(20);
        leases.acquire(task("a"), "A", List.of(key("b")), 0, 10);
        assertFalse(leases.acquire(task("b"), "B", List.of(key("c"), key("b"), key("a")), 1, 10).acquired());
        assertTrue(leases.snapshot().stream().noneMatch(l -> l.owner().equals(task("b"))));
    }
}
