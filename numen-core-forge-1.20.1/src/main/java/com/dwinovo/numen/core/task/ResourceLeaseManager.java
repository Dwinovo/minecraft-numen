package com.dwinovo.numen.core.task;

import java.util.*;

/** Server-thread lease table with deterministic acquisition order and overlap checks. */
public final class ResourceLeaseManager {
    public sealed interface Resource permits Resource.Key, Resource.Region {
        String sortKey();
        record Key(String type, String value, String display) implements Resource {
            public Key { Objects.requireNonNull(type); Objects.requireNonNull(value); }
            public String sortKey() { return type + ":" + value; }
        }
        record Region(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                      String display) implements Resource {
            public Region {
                if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("invalid region");
            }
            public String sortKey() { return "region:" + dimension + ":" + minX + ":" + minY + ":" + minZ + ":" + maxX + ":" + maxY + ":" + maxZ; }
            boolean overlaps(Region other) {
                return dimension.equals(other.dimension) && minX <= other.maxX && maxX >= other.minX
                        && minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ;
            }
        }
    }

    public record TaskKey(UUID companionUuid, String taskId) { }
    public record Waiter(TaskKey task, int priority, long since) { }
    public record Lease(UUID leaseId, TaskKey owner, String companionName, Resource resource,
                        long createdAt, long renewedAt, long expiresAt, int priority, List<Waiter> waiters) { }
    public record AcquireResult(boolean acquired, Lease blockingLease, Resource blockedResource) {
        static AcquireResult success() { return new AcquireResult(true, null, null); }
    }

    private final long ttlTicks;
    private final LinkedHashMap<UUID, Lease> leases = new LinkedHashMap<>();

    public ResourceLeaseManager(long ttlTicks) { this.ttlTicks = Math.max(1, ttlTicks); }

    public synchronized AcquireResult acquire(TaskKey task, String companionName, Collection<Resource> requested,
                                               long now, int priority) {
        sweep(now);
        List<Resource> resources = requested.stream().distinct().sorted(Comparator.comparing(Resource::sortKey)).toList();
        List<Lease> owned = leases.values().stream().filter(l -> l.owner().equals(task)).toList();
        if (owned.size() == resources.size() && owned.stream().allMatch(l -> resources.contains(l.resource()))) {
            renew(task, now); return AcquireResult.success();
        }
        for (Resource resource : resources) {
            Lease blocker = findBlocker(task, resource);
            if (blocker != null) {
                Waiter waiter = new Waiter(task, priority, now);
                if (blocker.waiters().stream().noneMatch(w -> w.task().equals(task))) {
                    ArrayList<Waiter> waiters = new ArrayList<>(blocker.waiters()); waiters.add(waiter);
                    blocker = new Lease(blocker.leaseId(), blocker.owner(), blocker.companionName(),
                            blocker.resource(), blocker.createdAt(), blocker.renewedAt(), blocker.expiresAt(), blocker.priority(), List.copyOf(waiters));
                    leases.put(blocker.leaseId(), blocker);
                }
                return new AcquireResult(false, blocker, resource);
            }
        }
        release(task);
        for (Resource resource : resources) {
            UUID id = UUID.randomUUID();
            leases.put(id, new Lease(id, task, companionName, resource, now, now, now + ttlTicks, priority, List.of()));
        }
        return AcquireResult.success();
    }

    public synchronized void renew(TaskKey owner, long now) {
        leases.replaceAll((id, lease) -> lease.owner().equals(owner)
                ? new Lease(id, lease.owner(), lease.companionName(), lease.resource(), lease.createdAt(), now,
                    now + ttlTicks, lease.priority(), lease.waiters()) : lease);
    }

    public synchronized int sweep(long now) {
        int before = leases.size();
        leases.values().removeIf(lease -> lease.expiresAt() <= now);
        return before - leases.size();
    }

    public synchronized void release(TaskKey owner) { leases.values().removeIf(l -> l.owner().equals(owner)); }
    public synchronized void releaseCompanion(UUID companion) { leases.values().removeIf(l -> l.owner().companionUuid().equals(companion)); }
    public synchronized void clear() { leases.clear(); }
    public synchronized List<Lease> snapshot() { return List.copyOf(leases.values()); }

    private Lease findBlocker(TaskKey task, Resource resource) {
        for (Lease lease : leases.values()) {
            if (lease.owner().equals(task)) continue;
            if (conflicts(resource, lease.resource())) return lease;
        }
        return null;
    }

    private static boolean conflicts(Resource a, Resource b) {
        if (a instanceof Resource.Key ak && b instanceof Resource.Key bk) return ak.type().equals(bk.type()) && ak.value().equals(bk.value());
        if (a instanceof Resource.Region ar && b instanceof Resource.Region br) return ar.overlaps(br);
        return false;
    }
}
