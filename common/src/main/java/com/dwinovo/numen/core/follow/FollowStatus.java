package com.dwinovo.numen.core.follow;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Immutable, entity-free owner-follow status shared by commands and tools.
 */
public record FollowStatus(
        UUID companionUuid,
        String companionName,
        boolean enabled,
        boolean manualPaused,
        boolean runtimeAvailable,
        FollowRuntimeState runtimeState,
        FollowWaitingReason waitingReason,
        boolean following,
        boolean navigationActive,
        boolean sprintAllowed,
        boolean catchingUp,
        long remainingCooldownTicks,
        boolean ownerPresent,
        boolean ownerOnline,
        boolean sameDimension,
        OptionalDouble distance,
        double effectiveStopDistance,
        double effectiveStartDistance,
        double sprintDistance,
        double catchUpDistance,
        double lostDistance,
        long failedCooldownTicks) {

    public FollowStatus {
        Objects.requireNonNull(companionUuid, "companionUuid");
        companionName = Objects.requireNonNullElse(companionName, "");
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(waitingReason, "waitingReason");
        distance = Objects.requireNonNull(distance, "distance");
        remainingCooldownTicks = Math.max(0L, remainingCooldownTicks);
    }

    public String compactText() {
        String distanceText = distance.isPresent()
                ? String.format(Locale.ROOT, "%.2f", distance.getAsDouble())
                : "unavailable";
        return "同伴 " + companionName
                + "：enabled=" + enabled
                + ", paused=" + manualPaused
                + ", 运行=" + runtimeDescription()
                + ", waiting=" + waitingDescription()
                + ", navigation=" + navigationActive
                + ", ownerOnline=" + ownerOnline
                + ", sameDimension=" + sameDimension
                + ", distance=" + distanceText
                + ", cooldown=" + remainingCooldownTicks
                + ", thresholds={stop=" + effectiveStopDistance
                + ", start=" + effectiveStartDistance
                + ", sprint=" + sprintDistance
                + ", catchUp=" + catchUpDistance
                + ", lost=" + lostDistance
                + ", failedCooldown=" + failedCooldownTicks + "}";
    }

    private String runtimeDescription() {
        return switch (runtimeState) {
            case DISABLED -> "自动跟随已关闭";
            case MANUALLY_PAUSED -> "自动跟随已手动暂停";
            case WAITING_FOR_OWNER -> "正在等待可跟随的主人";
            case IDLE_NEAR_OWNER -> "已在主人附近，无需移动";
            case FOLLOWING -> navigationActive
                    ? "正在跟随并导航"
                    : "已取得跟随资格，等待建立路径";
            case FAILED_COOLDOWN -> "最近导航失败，正在冷却后重试";
        };
    }

    private String waitingDescription() {
        return switch (waitingReason) {
            case NONE -> "无";
            case OWNER_OFFLINE -> "主人当前不在线";
            case OWNER_OTHER_DIMENSION -> "主人位于其他维度";
            case OWNER_TOO_FAR -> "主人超过最大跟随距离";
            case OWNER_INVALID -> "主人绑定或运行身份不可用";
            case COMPANION_NOT_ACTIVE -> "同伴当前不可运行";
        };
    }
}
