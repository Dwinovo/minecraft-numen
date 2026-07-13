package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;

import java.util.*;

/** Maps game tasks onto server-authoritative expiring resource leases. */
final class CompanionWorkCoordinator {
    private static final long LEASE_TTL_TICKS = 20L * 15L;
    private static final ResourceLeaseManager LEASES = new ResourceLeaseManager(LEASE_TTL_TICKS);

    private CompanionWorkCoordinator() { }

    static Optional<String> tryAcquire(NumenPlayer player, TaskRecord record) {
        long now = player.level.getGameTime();
        var result = LEASES.acquire(key(player.getUUID(), record.getToolCallId()), player.getName().getString(),
                resources(player, record), now, priority(record));
        if (result.acquired()) return Optional.empty();
        String resource = display(result.blockedResource());
        return Optional.of("正在等待伙伴 " + result.blockingLease().companionName() + " 释放" + resource);
    }

    static void renew(UUID companionUuid, String toolCallId, long now) { LEASES.renew(key(companionUuid, toolCallId), now); }
    static void sweep(long now) { LEASES.sweep(now); }
    static void release(UUID companionUuid, String toolCallId) { LEASES.release(key(companionUuid, toolCallId)); }
    static void releaseCompanion(UUID companionUuid) { LEASES.releaseCompanion(companionUuid); }
    static void clear() { LEASES.clear(); }

    private static ResourceLeaseManager.TaskKey key(UUID companion, String task) {
        return new ResourceLeaseManager.TaskKey(companion, task);
    }

    private static List<ResourceLeaseManager.Resource> resources(NumenPlayer player, TaskRecord record) {
        String dimension = player.level.dimension().location().toString();
        String owner = String.valueOf(player.getOwnerUuid());
        ArrayList<ResourceLeaseManager.Resource> resources = new ArrayList<>();
        if (record instanceof PlaceBlockTaskRecord place) resources.add(block(dimension, place.pos, "位于 " + xyz(place.pos) + " 的方块"));
        if (record instanceof BreakBlockTaskRecord breaking) resources.add(block(dimension, breaking.target, "位于 " + xyz(breaking.target) + " 的方块"));
        if (record instanceof InteractAtTaskRecord interact && interact.aim != null)
            resources.add(new ResourceLeaseManager.Resource.Key("workstation", dimension + ":" + interact.aim.asLong(), "位于 " + xyz(interact.aim) + " 的工作站"));
        if (record instanceof BuildBlueprintTaskRecord build) {
            BlockPos a = build.anchor;
            // Exact dimensions are validated again by the build planner; a conservative anchor cell
            // still prevents identical builds, while the Region type supports precise plans as they become available.
            resources.add(new ResourceLeaseManager.Resource.Region(dimension, a.getX(), a.getY(), a.getZ(),
                    a.getX(), a.getY(), a.getZ(), "位于 " + xyz(a) + " 的建造区域"));
        }
        if (record instanceof CraftItemsTaskRecord craft)
            resources.add(new ResourceLeaseManager.Resource.Key("owner-item", owner + ":craft:" + craft.target, "合成材料 " + craft.target));
        if (record instanceof MineBlockTaskRecord mine)
            resources.add(new ResourceLeaseManager.Resource.Key("owner-target", owner + ":mine:" + mine.label, "采集目标 " + mine.label));
        if (record instanceof CollectItemsTaskRecord collect && !collect.filter.isEmpty())
            resources.add(new ResourceLeaseManager.Resource.Key("owner-target", owner + ":collect:" + collect.label, "拾取目标 " + collect.label));
        return List.copyOf(resources);
    }

    private static ResourceLeaseManager.Resource block(String dimension, BlockPos pos, String display) {
        return new ResourceLeaseManager.Resource.Key("block", dimension + ":" + pos.asLong(), display);
    }
    private static int priority(TaskRecord record) { return record.wasActiveBeforeRestart() ? 100 : 50; }
    private static String xyz(BlockPos pos) { return pos.getX() + "/" + pos.getY() + "/" + pos.getZ(); }
    private static String display(ResourceLeaseManager.Resource resource) {
        if (resource instanceof ResourceLeaseManager.Resource.Key key) return key.display();
        return ((ResourceLeaseManager.Resource.Region) resource).display();
    }
}
