package com.dwinovo.numen.core.follow;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Runtime-only facade for lifecycle and status integrations.
 *
 * <p>Persistent follow intent remains the responsibility of
 * {@link FollowStateStore}; this facade cannot enable, disable, pause, or
 * otherwise mutate that intent.
 */
public final class FollowService {

    private FollowService() {}

    public static boolean releaseRuntime(
            MinecraftServer server, UUID companionUuid, FollowReleaseReason reason) {
        MinecraftServer checkedServer = requireServer(server);
        UUID checkedUuid = requireUuid(companionUuid);
        FollowReleaseReason checkedReason = requireReason(reason);
        return FollowStateStore.get(checkedServer)
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

    /**
     * Applies one user control to the current body. The tool and command both
     * enter through this method; navigation remains scheduler-owned.
     */
    public static FollowControlResult apply(
            MinecraftServer server,
            NumenPlayer companion,
            FollowAction action,
            FollowConfig config) {
        MinecraftServer checkedServer = requireServer(server);
        NumenPlayer checkedCompanion =
                Objects.requireNonNull(companion, "companion");
        FollowAction checkedAction = Objects.requireNonNull(action, "action");
        FollowConfig checkedConfig = Objects.requireNonNull(config, "config");
        return apply(
                FollowStateStore.get(checkedServer),
                subject(checkedCompanion),
                checkedAction,
                checkedConfig);
    }

    public static FollowStatus status(
            MinecraftServer server,
            NumenPlayer companion,
            FollowConfig config) {
        MinecraftServer checkedServer = requireServer(server);
        NumenPlayer checkedCompanion =
                Objects.requireNonNull(companion, "companion");
        FollowConfig checkedConfig = Objects.requireNonNull(config, "config");
        return status(
                FollowStateStore.get(checkedServer),
                subject(checkedCompanion),
                checkedConfig);
    }

    static FollowControlResult apply(
            FollowStateStore store,
            Subject subject,
            FollowAction action,
            FollowConfig config) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(config, "config");

        FollowState before = store.getOrDefault(subject.companionUuid());
        if (action == FollowAction.STATUS) {
            FollowStatus status = status(store, subject, config);
            return result(action, true, false, "STATUS",
                    status.compactText(), status);
        }
        if (!subject.companionValid() || !subject.ownerPresent()) {
            FollowStatus status = status(store, subject, config);
            return result(action, false, false, "INVALID_COMPANION",
                    "当前同伴或主人绑定不可用于自动跟随控制。", status);
        }

        return switch (action) {
            case ON -> {
                boolean changed = store.setControlState(
                        subject.companionUuid(), true, false);
                FollowStatus status = status(store, subject, config);
                yield result(action, true, changed,
                        changed ? "ENABLED" : "ALREADY_ENABLED",
                        changed
                                ? "已开启自动跟随；移动将在后台调度器下次评估时开始。"
                                : "自动跟随已经开启。",
                        status);
            }
            case OFF -> {
                boolean changed = store.setControlState(
                        subject.companionUuid(), false, false);
                boolean released = store.releaseRuntime(
                        subject.companionUuid(), FollowReleaseReason.FOLLOW_DISABLED);
                FollowStatus status = status(store, subject, config);
                yield released
                        ? result(action, true, changed,
                                changed ? "DISABLED" : "ALREADY_DISABLED",
                                changed
                                        ? "已关闭自动跟随并释放当前移动控制。"
                                        : "自动跟随已经关闭；已再次清理运行控制。",
                                status)
                        : releaseWarning(action, changed, status);
            }
            case PAUSE -> {
                if (!before.enabled()) {
                    FollowStatus status = status(store, subject, config);
                    yield result(action, false, false,
                            "PAUSE_REQUIRES_ENABLED",
                            "自动跟随当前已关闭；请使用 on 或 resume 启用。",
                            status);
                }
                boolean changed = store.setControlState(
                        subject.companionUuid(), true, true);
                boolean released = store.releaseRuntime(
                        subject.companionUuid(), FollowReleaseReason.MANUAL_PAUSE);
                FollowStatus status = status(store, subject, config);
                yield released
                        ? result(action, true, changed,
                                changed ? "PAUSED" : "ALREADY_PAUSED",
                                changed
                                        ? "已暂停自动跟随并释放当前移动控制。"
                                        : "自动跟随已经暂停；已再次清理运行控制。",
                                status)
                        : releaseWarning(action, changed, status);
            }
            case RESUME -> {
                boolean changed = store.setControlState(
                        subject.companionUuid(), true, false);
                FollowStatus status = status(store, subject, config);
                yield result(action, true, changed,
                        changed ? "RESUMED" : "ALREADY_RESUMED",
                        changed
                                ? "已恢复自动跟随；移动将在后台调度器下次评估时继续。"
                                : "自动跟随已经处于可运行状态。",
                        status);
            }
            case STATUS -> throw new IllegalStateException(
                    "status handled before mutable controls");
        };
    }

    static FollowStatus status(
            FollowStateStore store, Subject subject, FollowConfig config) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(config, "config");

        FollowState state = store.getOrDefault(subject.companionUuid());
        FollowDecisions.Distances distances = FollowDecisions.resolveDistances(
                state.stopDistanceOverride(), state.startDistanceOverride(), config);
        Optional<FollowRuntimeSnapshot> runtime =
                store.runtimeSnapshot(subject.companionUuid(), subject.gameTime());

        FollowRuntimeSnapshot snapshot = runtime.orElseGet(
                () -> conservativeSnapshot(state, subject, config));
        return new FollowStatus(
                subject.companionUuid(),
                subject.companionName(),
                state.enabled(),
                state.manualPaused(),
                runtime.isPresent(),
                snapshot.runtimeState(),
                snapshot.waitingReason(),
                snapshot.following(),
                snapshot.navigationActive(),
                snapshot.sprintAllowed(),
                snapshot.catchingUp(),
                snapshot.remainingCooldownTicks(),
                subject.ownerPresent(),
                subject.ownerOnline(),
                subject.sameDimension(),
                subject.distance(),
                distances.stop(),
                distances.start(),
                config.sprintDistance(),
                config.catchUpDistance(),
                config.lostDistance(),
                config.failedCooldownTicks());
    }

    private static FollowRuntimeSnapshot conservativeSnapshot(
            FollowState state, Subject subject, FollowConfig config) {
        FollowRuntimeState runtimeState;
        FollowWaitingReason waitingReason;
        if (!state.enabled()) {
            runtimeState = FollowRuntimeState.DISABLED;
            waitingReason = FollowWaitingReason.NONE;
        } else if (state.manualPaused()) {
            runtimeState = FollowRuntimeState.MANUALLY_PAUSED;
            waitingReason = FollowWaitingReason.NONE;
        } else if (!subject.companionValid()) {
            runtimeState = FollowRuntimeState.WAITING_FOR_OWNER;
            waitingReason = FollowWaitingReason.COMPANION_NOT_ACTIVE;
        } else if (!subject.ownerPresent() || !subject.ownerValid()) {
            runtimeState = FollowRuntimeState.WAITING_FOR_OWNER;
            waitingReason = subject.ownerPresent() && !subject.ownerOnline()
                    ? FollowWaitingReason.OWNER_OFFLINE
                    : FollowWaitingReason.OWNER_INVALID;
        } else if (!subject.sameDimension()) {
            runtimeState = FollowRuntimeState.WAITING_FOR_OWNER;
            waitingReason = FollowWaitingReason.OWNER_OTHER_DIMENSION;
        } else if (subject.distance().isPresent()
                && subject.distance().getAsDouble() >= config.lostDistance()) {
            runtimeState = FollowRuntimeState.WAITING_FOR_OWNER;
            waitingReason = FollowWaitingReason.OWNER_TOO_FAR;
        } else {
            runtimeState = FollowRuntimeState.WAITING_FOR_OWNER;
            waitingReason = FollowWaitingReason.OWNER_INVALID;
        }
        return new FollowRuntimeSnapshot(
                runtimeState,
                waitingReason,
                false,
                false,
                false,
                false,
                FollowDecisions.NO_FAILED_COOLDOWN,
                0L);
    }

    private static Subject subject(NumenPlayer companion) {
        boolean companionValid = !companion.isRemoved() && companion.isAlive();
        boolean ownerPresent = companion.getOwnerUuid() != null;
        ServerPlayer owner = ownerPresent ? companion.resolveOwnerPlayer() : null;
        boolean ownerOnline = owner != null;
        boolean ownerValid =
                ownerOnline && !owner.isRemoved() && owner.isAlive();
        boolean sameDimension =
                ownerValid && owner.level() == companion.level();
        OptionalDouble distance = sameDimension
                ? OptionalDouble.of(FollowDecisions.distance3d(
                        companion.getX(), companion.getY(), companion.getZ(),
                        owner.getX(), owner.getY(), owner.getZ()))
                : OptionalDouble.empty();
        return new Subject(
                companion.getUUID(),
                companion.getName().getString(),
                companionValid,
                ownerPresent,
                ownerOnline,
                ownerValid,
                sameDimension,
                distance,
                companion.level().getGameTime());
    }

    private static FollowControlResult releaseWarning(
            FollowAction action, boolean changed, FollowStatus status) {
        return result(
                action,
                true,
                changed,
                "RUNTIME_RELEASE_WARNING",
                "跟随意图已保存，但运行控制未能确认完全释放；请检查服务器日志。",
                status);
    }

    private static FollowControlResult result(
            FollowAction action,
            boolean success,
            boolean changed,
            String code,
            String message,
            FollowStatus status) {
        return new FollowControlResult(
                action, success, changed, code, message, status);
    }

    record Subject(
            UUID companionUuid,
            String companionName,
            boolean companionValid,
            boolean ownerPresent,
            boolean ownerOnline,
            boolean ownerValid,
            boolean sameDimension,
            OptionalDouble distance,
            long gameTime) {

        Subject {
            Objects.requireNonNull(companionUuid, "companionUuid");
            companionName = Objects.requireNonNullElse(companionName, "");
            distance = Objects.requireNonNull(distance, "distance");
        }
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
