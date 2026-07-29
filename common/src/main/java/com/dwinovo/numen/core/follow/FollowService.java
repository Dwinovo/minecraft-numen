package com.dwinovo.numen.core.follow;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/**
 * Runtime-only facade for lifecycle and status integrations.
 *
 * <p>Persistent follow intent remains the responsibility of
 * {@link FollowStateStore}; this facade cannot enable, disable, pause, or
 * otherwise mutate that intent.
 */
public final class FollowService {

    private FollowService() {}

    public static void releaseRuntime(
            MinecraftServer server, UUID companionUuid, FollowReleaseReason reason) {
        MinecraftServer checkedServer = requireServer(server);
        UUID checkedUuid = requireUuid(companionUuid);
        FollowReleaseReason checkedReason = requireReason(reason);
        FollowStateStore.get(checkedServer)
                .releaseRuntime(checkedUuid, checkedReason);
    }

    public static void removeRuntime(
            MinecraftServer server, UUID companionUuid, FollowReleaseReason reason) {
        MinecraftServer checkedServer = requireServer(server);
        UUID checkedUuid = requireUuid(companionUuid);
        FollowReleaseReason checkedReason = requireReason(reason);
        FollowStateStore.get(checkedServer)
                .removeRuntime(checkedUuid, checkedReason);
    }

    public static int releaseAllRuntime(
            MinecraftServer server, FollowReleaseReason reason) {
        MinecraftServer checkedServer = requireServer(server);
        FollowReleaseReason checkedReason = requireReason(reason);
        return FollowStateStore.get(checkedServer)
                .releaseAllRuntime(checkedReason);
    }

    public static Optional<FollowRuntimeSnapshot> runtimeSnapshot(
            MinecraftServer server, UUID companionUuid, long currentGameTime) {
        MinecraftServer checkedServer = requireServer(server);
        UUID checkedUuid = requireUuid(companionUuid);
        return FollowStateStore.get(checkedServer)
                .runtimeSnapshot(checkedUuid, currentGameTime);
    }

    private static MinecraftServer requireServer(MinecraftServer server) {
        return Objects.requireNonNull(server, "server");
    }

    private static UUID requireUuid(UUID companionUuid) {
        return Objects.requireNonNull(companionUuid, "companionUuid");
    }

    private static FollowReleaseReason requireReason(FollowReleaseReason reason) {
        return Objects.requireNonNull(reason, "reason");
    }
}
