package com.dwinovo.numen.core.task;

/** Derives bounded task-page telemetry without serializing runtime executors. */
final class TaskUiDetails {
    record Details(int current, int total, String phase, String blocker, int etaSeconds) { }

    private TaskUiDetails() { }

    static Details of(TaskRecord record, long now, boolean active, boolean queuePaused,
                      boolean inventoryLocked) {
        int current = current(record, now);
        int total = total(record);
        String blocker = record.getUiBlocker();
        if (queuePaused) blocker = "队列已暂停";
        else if (inventoryLocked) blocker = "玩家正在整理伙伴背包";
        else if (record.getState() == TaskState.PAUSED) blocker = "任务已暂停";
        else if (blocker.isBlank() && record.getFailureDetail() != null) blocker = record.getFailureDetail();
        String phase = phase(record, active, blocker);
        int eta = eta(record, now, current, total);
        return new Details(current, total, phase, blocker, eta);
    }

    static int current(TaskRecord record, long now) {
        if (record instanceof MineBlockTaskRecord mine) return mine.getMined();
        if (record instanceof HuntTaskRecord hunt) return hunt.getKilled();
        if (record instanceof ShootTaskRecord shoot) return shoot.getDestroyed();
        if (record instanceof CraftItemsTaskRecord craft) return craft.getProduced();
        if (record instanceof BuildBlueprintTaskRecord build) return build.getChanged() + build.getSkipped();
        if (record instanceof CollectItemsTaskRecord collect) return collect.getCollected();
        if (record instanceof WaitTaskRecord wait && record.getUiStartedGameTime() >= 0) {
            return Math.min(wait.seconds, (int) Math.max(0L, now - record.getUiStartedGameTime()) / 20);
        }
        return 0;
    }

    private static int total(TaskRecord record) {
        if (record instanceof MineBlockTaskRecord mine) return mine.count;
        if (record instanceof HuntTaskRecord hunt) return hunt.count;
        if (record instanceof ShootTaskRecord shoot) return shoot.count;
        if (record instanceof CraftItemsTaskRecord craft) return craft.count;
        if (record instanceof BuildBlueprintTaskRecord build) return build.batchLimit;
        if (record instanceof WaitTaskRecord wait) return wait.seconds;
        if (record instanceof PlaceBlockTaskRecord || record instanceof BreakBlockTaskRecord
                || record instanceof InteractAtTaskRecord) return 1;
        return -1;
    }

    private static String phase(TaskRecord record, boolean active, String blocker) {
        if (!blocker.isBlank()) return "等待";
        if (record instanceof CraftItemsTaskRecord craft) return craft.getPhase().name().toLowerCase();
        if (record instanceof WaitTaskRecord) return "等待计时";
        return active ? "执行中" : "排队中";
    }

    private static int eta(TaskRecord record, long now, int current, int total) {
        if (total <= 0 || record.getUiStartedGameTime() < 0) return -1;
        int progressed = current - record.getUiStartedProgress();
        if (progressed <= 0) {
            if (record instanceof WaitTaskRecord) return Math.max(0, total - current);
            return -1;
        }
        long elapsedTicks = Math.max(1L, now - record.getUiStartedGameTime());
        long remaining = Math.max(0, total - current);
        return (int) Math.min(86_400L, Math.max(0L, remaining * elapsedTicks / progressed / 20L));
    }
}
