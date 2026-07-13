package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/**
 * numen-core's server-tick driver of companion tasks. The engine ({@code numen-api})
 * owns the body and is a pure scheduler; <em>task execution</em> is core's, so the
 * per-companion task queue lives here (keyed by companion UUID), not on the body.
 *
 * <p>Each tick, for every live {@link NumenPlayer}: pull the head of its queue,
 * run the matching {@link CompanionTask} to completion (deadline-bounded), and
 * ship finished results back to the owner as {@link TaskResultPayload} — core's
 * own packet. Registered from core's end-of-tick hooks; finalised on body
 * removal / death / owner-abort via the engine's {@code CompanionLifecycle} seam.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionTickDispatcher {

    public record TaskKey(UUID companionUuid, String toolCallId) {}
    private record Running(CompanionTask task, TaskRecord record, TaskProgressWatchdog watchdog) {}

    private static final Map<UUID, Running> ACTIVE = new HashMap<>();
    /** Per-companion task queue — replaces the body-hosted queue the engine no longer keeps. */
    private static final Map<UUID, TaskQueue> QUEUES = new HashMap<>();
    /** companion UUID -> game time when scheduler pause began. */
    private static final Map<UUID, Long> PAUSED_AT = new HashMap<>();
    /** Transient owner inventory sessions; tasks wait without consuming their timeout budget. */
    private static final Map<UUID, Long> INVENTORY_LOCKED_AT = new HashMap<>();
    /** Recently completed calls survive reconnects; insertion ordered and bounded. */
    private static final LinkedHashMap<TaskKey, String> COMPLETED_RESULTS = new LinkedHashMap<>();
    private static final Map<UUID, Long> UI_REVISIONS = new HashMap<>();
    private static final Map<UUID, Integer> LAST_UI_SIGNATURES = new HashMap<>();
    private static final Map<UUID, Long> LAST_UI_PUSH_AT = new HashMap<>();
    private static final int MAX_COMPLETED_RESULTS = 256;
    private static final long ACTIVE_CHECKPOINT_TICKS = 20L * 20L;
    private static long lastPersistGameTime = Long.MIN_VALUE;
    private static boolean persistenceDirty;
    private static MinecraftServer loadedServer;
    private static boolean shuttingDown;

    private CompanionTickDispatcher() {}

    /** The companion's task queue (created on first use). Body-bound tools enqueue here. */
    public static TaskQueue queueFor(UUID companionUuid) {
        return QUEUES.computeIfAbsent(companionUuid, k -> new TaskQueue());
    }

    public static void tick(MinecraftServer server) {
        ensureLoaded(server);
        com.dwinovo.numen.entity.Companions.tickRespawns(server);   // timed death recoveries
        CompanionWorkCoordinator.sweep(server.overworld().getGameTime());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer ap) {
                tickOne(ap);
            }
        }
        persistPeriodically(server);
    }

    /** Restore persisted queues once per server instance, before tick or packet handling. */
    public static void ensureLoaded(MinecraftServer server) {
        if (server == null || loadedServer == server) return;
        clearRuntime();
        loadedServer = server;
        TaskStateStore.Loaded loaded = TaskStateStore.load(server);
        COMPLETED_RESULTS.putAll(loaded.results());
        trimCompletedResults();
        long now = server.overworld().getGameTime();
        for (TaskStateStore.RestoredQueue restored : loaded.queues()) {
            TaskQueue queue = queueFor(restored.companionUuid());
            for (TaskRecord record : restored.records()) {
                if (record instanceof InterruptedTaskRecord interrupted) {
                    rememberResult(new TaskKey(restored.companionUuid(), record.getToolCallId()),
                            TaskResult.cancelled(interrupted.message, "restart_interrupted",
                                    Map.of("tool", record.getToolName())).toJson());
                } else {
                    queue.enqueue(record);
                }
            }
            if (restored.paused()) PAUSED_AT.put(restored.companionUuid(), now);
        }
        if (!loaded.queues().isEmpty() || !loaded.results().isEmpty()) {
            com.dwinovo.numen.core.Constants.LOG.info(
                    "Restored {} companion task queue(s) and {} completed result(s)",
                    loaded.queues().size(), loaded.results().size());
        }
    }

    /** Persist and release static world-bound state at server shutdown. */
    public static void prepareForShutdown(MinecraftServer server) {
        persistNow(server);
        shuttingDown = true;
    }

    public static void shutdown(MinecraftServer server) {
        if (!shuttingDown) persistNow(server);
        clearRuntime();
        loadedServer = null;
        shuttingDown = false;
    }

    private static void clearRuntime() {
        ACTIVE.clear(); QUEUES.clear(); PAUSED_AT.clear(); INVENTORY_LOCKED_AT.clear();
        UI_REVISIONS.clear(); LAST_UI_SIGNATURES.clear(); LAST_UI_PUSH_AT.clear();
        COMPLETED_RESULTS.clear(); CompanionWorkCoordinator.clear();
        lastPersistGameTime = Long.MIN_VALUE;
        persistenceDirty = false;
    }

    /**
     * Handle an API/client restart attachment before invoking a tool again.
     * Existing work is left untouched; cached completion is replayed; only a
     * missing explicitly-safe call may be rebuilt from its original arguments.
     */
    public static boolean handleDispatch(MinecraftServer server, UUID companionUuid,
                                         String toolCallId, String toolName, String argumentsJson,
                                         boolean reconnect, Consumer<String> reply) {
        ensureLoaded(server);
        TaskKey key = new TaskKey(companionUuid, toolCallId);
        String completed = COMPLETED_RESULTS.get(key);
        if (completed != null) {
            reply.accept(completed);
            return true;
        }
        TaskRecord existing = findRecord(companionUuid, toolCallId);
        if (existing != null) {
            if (!existing.getToolName().equals(toolName)) {
                reply.accept(TaskResult.fail("tool_call_id already belongs to " + existing.getToolName()).toJson());
            } else if (!existing.getArgumentsJson().equals("{}")
                    && !existing.getArgumentsJson().equals(argumentsJson)) {
                reply.accept(TaskResult.fail("tool_call_id was re-used with different arguments").toJson());
            }
            return true;
        }
        if (reconnect && (toolName.equals(MineBlockTaskRecord.TOOL_NAME)
                || toolName.equals(CollectItemsTaskRecord.TOOL_NAME)
                || toolName.equals(CraftItemsTaskRecord.TOOL_NAME))) {
            String json = TaskResult.cancelled(
                    "interrupted: persisted progress for " + toolName + " is unavailable; refusing to repeat collection side effects",
                    "restart_state_missing", Map.of("tool", toolName)).toJson();
            rememberResult(key, json);
            persistNow(server);
            reply.accept(json);
            return true;
        }
        return false;
    }

    public static TaskRecord findRecord(UUID companionUuid, String toolCallId) {
        Running active = ACTIVE.get(companionUuid);
        if (active != null && toolCallId.equals(active.record().getToolCallId())) return active.record();
        TaskQueue queue = QUEUES.get(companionUuid);
        return queue == null ? null : queue.find(toolCallId);
    }

    /** Capture raw arguments on the record just enqueued by the tool adapter. */
    public static void attachArguments(UUID companionUuid, String toolCallId, String argumentsJson) {
        TaskRecord record = findRecord(companionUuid, toolCallId);
        if (record != null) {
            record.setArgumentsJson(argumentsJson);
            persistNow(loadedServer);
        }
    }

    public static boolean pauseFor(NumenPlayer player) {
        UUID id = player.getUUID();
        boolean changed = !PAUSED_AT.containsKey(id);
        if (changed) PAUSED_AT.put(id, player.level.getGameTime());
        com.dwinovo.numen.core.pathing.exec.InputDriver.halt(player);
        persistNow(player.level.getServer());
        return changed;
    }

    public static boolean resumeFor(NumenPlayer player) {
        UUID id = player.getUUID();
        Long pausedAt = PAUSED_AT.remove(id);
        boolean changed = pausedAt != null;
        Running running = ACTIVE.get(id);
        if (changed) {
            long pausedTicks = Math.max(0L, player.level.getGameTime() - pausedAt);
            queueFor(id).shiftDeadlinesBy(pausedTicks);
            if (running != null && running.record().getState() == TaskState.RUNNING) {
                running.record().shiftDeadlineBy(pausedTicks);
                running.watchdog().start(player.position(), player.level.getGameTime());
                running.task().onResume(pausedTicks);
                running.task().recoverFromStuck();
            }
        }
        persistNow(player.level.getServer());
        return changed;
    }

    public static boolean isPaused(UUID companionUuid) {
        return PAUSED_AT.containsKey(companionUuid);
    }

    public static boolean isInventoryLocked(UUID companionUuid) {
        return INVENTORY_LOCKED_AT.containsKey(companionUuid);
    }

    /** Native menu lifecycle hook; all calls occur on the server thread. */
    public static void setInventorySession(NumenPlayer player, boolean opened) {
        UUID id = player.getUUID();
        long now = player.level.getGameTime();
        if (opened) {
            INVENTORY_LOCKED_AT.putIfAbsent(id, now);
            com.dwinovo.numen.core.pathing.exec.InputDriver.halt(player);
            bumpUiRevision(id);
            pushTaskSnapshot(player, true);
            return;
        }
        Long lockedAt = INVENTORY_LOCKED_AT.remove(id);
        if (lockedAt == null) return;
        long lockedTicks = Math.max(0L, now - lockedAt);
        queueFor(id).shiftDeadlinesBy(lockedTicks);
        Running running = ACTIVE.get(id);
        if (running != null && running.record().getState() == TaskState.RUNNING) {
            running.record().shiftDeadlineBy(lockedTicks);
            running.watchdog().start(player.position(), now);
            running.task().onResume(lockedTicks);
            running.task().recoverFromStuck();
        }
        bumpUiRevision(id);
        pushTaskSnapshot(player, true);
    }

    public static boolean pauseTask(NumenPlayer player, String toolCallId) {
        UUID id = player.getUUID(); long now = player.level.getGameTime();
        Running running = ACTIVE.get(id);
        if (running != null && running.record().getToolCallId().equals(toolCallId)) {
            running.task().recoverFromStuck();
            com.dwinovo.numen.core.pathing.exec.InputDriver.halt(player);
            ACTIVE.remove(id); CompanionWorkCoordinator.release(id, running.record().getToolCallId());
            running.record().pauseAt(now); queueFor(id).enqueueFirst(running.record()); persistNow(player.level.getServer()); return true;
        }
        TaskRecord record = queueFor(id).find(toolCallId);
        if (record != null && record.getState() == TaskState.PENDING) { record.pauseAt(now); persistNow(player.level.getServer()); return true; }
        return false;
    }

    public static boolean resumeTask(NumenPlayer player, String toolCallId) {
        TaskRecord record = queueFor(player.getUUID()).find(toolCallId);
        if (record != null && record.getState() == TaskState.PAUSED) { record.resumeAt(player.level.getGameTime()); persistNow(player.level.getServer()); return true; }
        return false;
    }

    public static boolean cancelTask(NumenPlayer player, String toolCallId) {
        UUID id=player.getUUID(); Running running=ACTIVE.get(id);
        if(running!=null&&running.record().getToolCallId().equals(toolCallId)){running.record().markFailure(TaskFailureCode.CANCELLED,"cancelled by owner from task page");running.record().setState(TaskState.CANCELLED);return true;}
        boolean changed=queueFor(id).cancel(toolCallId,"cancelled by owner from task page");if(changed){drainResults(player);persistNow(player.level.getServer());}return changed;
    }

    public static long uiRevision(UUID companionUuid) {
        return UI_REVISIONS.getOrDefault(companionUuid, 0L);
    }

    private static void bumpUiRevision(UUID companionUuid) {
        UI_REVISIONS.merge(companionUuid, 1L, Long::sum);
    }

    public static List<com.dwinovo.numen.network.payload.TaskListPayload.Entry> uiTasks(UUID companionUuid) {
        java.util.ArrayList<com.dwinovo.numen.network.payload.TaskListPayload.Entry> out=new java.util.ArrayList<>();
        long now = loadedServer == null ? 0L : loadedServer.overworld().getGameTime();
        boolean queuePaused = PAUSED_AT.containsKey(companionUuid);
        boolean inventoryLocked = INVENTORY_LOCKED_AT.containsKey(companionUuid);
        Running running=ACTIVE.get(companionUuid);if(running!=null){TaskRecord r=running.record();TaskUiDetails.Details d=TaskUiDetails.of(r,now,true,queuePaused,inventoryLocked);out.add(new com.dwinovo.numen.network.payload.TaskListPayload.Entry(r.getToolCallId(),r.getToolName(),r.describe(),r.getState().name(),true,false,d.current(),d.total(),d.phase(),d.blocker(),d.etaSeconds()));}
        TaskQueue q=QUEUES.get(companionUuid);if(q!=null)for(TaskRecord r:q.pendingSnapshot()){TaskUiDetails.Details d=TaskUiDetails.of(r,now,false,queuePaused,inventoryLocked);out.add(new com.dwinovo.numen.network.payload.TaskListPayload.Entry(r.getToolCallId(),r.getToolName(),r.describe(),r.getState().name(),false,r.getState()==TaskState.PAUSED,d.current(),d.total(),d.phase(),d.blocker(),d.etaSeconds()));}
        return List.copyOf(out);
    }

    /**
     * Drop a companion's running task WITHOUT shipping a result — used on death, where the client's
     * {@code NumenDeathPayload} already resolves the in-flight tool call with the death cause (so a
     * second result here would be a duplicate the client ignores).
     */
    public static void clearActiveTask(NumenPlayer player) {
        Running running = ACTIVE.remove(player.getUUID());
        if (running != null) CompanionWorkCoordinator.release(player.getUUID(), running.record().getToolCallId());
        CompanionWorkCoordinator.releaseCompanion(player.getUUID());
    }

    /** Owner pressed Stop: cancel the running task and drop the queue for this companion. */
    public static void cancelFor(NumenPlayer player) {
        UUID id = player.getUUID();
        PAUSED_AT.remove(id);
        queueFor(id).cancelAll("interrupted by owner");
        Running running = ACTIVE.get(id);
        if (running != null && running.record().getState() == TaskState.RUNNING) {
            running.record().markFailure(TaskFailureCode.CANCELLED, "interrupted by owner");
            running.record().setState(TaskState.CANCELLED);
        }
    }

    /**
     * Finalize a companion's running task because the BODY is leaving the world
     * (dormancy / dismissal / death) — the tick loop only visits players still in
     * the player list, so without this the running task is orphaned in {@link
     * #ACTIVE} and its {@code buildResult} side-effects never run (e.g. a mining
     * dig's crack overlay would stay painted on every viewer until chunk reload).
     */
    public static void onCompanionRemoved(NumenPlayer player) {
        if (shuttingDown) {
            com.dwinovo.numen.core.pathing.exec.InputDriver.halt(player);
            CompanionWorkCoordinator.releaseCompanion(player.getUUID());
            return;
        }
        UUID id = player.getUUID();
        Running running = ACTIVE.remove(id);
        if (running != null) {
            CompanionWorkCoordinator.release(id, running.record().getToolCallId());
            TaskState st = running.record().getState();
            if (st == TaskState.RUNNING || st == TaskState.PENDING || st == TaskState.PAUSED) {
                st = TaskState.CANCELLED;
                running.record().setState(st);
            }
            running.record().setResult(running.task().buildResult(st));
            TaskResult result = TaskEvidence.decorate(player, running.record(), running.record().getResult());
            running.record().setResult(result);
            rememberResult(new TaskKey(id, running.record().getToolCallId()),
                    result == null ? "{\"success\":false,\"message\":\"no result produced\"}" : result.toJson());
            queueFor(id).complete(running.record());
            drainResults(player);
        }
        QUEUES.remove(id);   // the body is gone; don't leak its queue
        PAUSED_AT.remove(id);
        CompanionWorkCoordinator.releaseCompanion(id);
        persistNow(player.level.getServer());
    }

    private static void tickOne(NumenPlayer player) {
        UUID id = player.getUUID();
        Running running = ACTIVE.get(id);
        if (PAUSED_AT.containsKey(id)) {
            com.dwinovo.numen.core.pathing.exec.InputDriver.halt(player);
            drainResults(player);
            pushTaskSnapshot(player, false);
            return;
        }
        if (INVENTORY_LOCKED_AT.containsKey(id)) {
            com.dwinovo.numen.core.pathing.exec.InputDriver.halt(player);
            drainResults(player);
            pushTaskSnapshot(player, false);
            return;
        }

        if (running == null) {
            TaskRecord rec = queueFor(id).pollHead();
            if (rec != null) {
                java.util.Optional<String> blocked = CompanionWorkCoordinator.tryAcquire(player, rec);
                if (blocked.isPresent()) {
                    rec.setUiBlocker(blocked.get());
                    queueFor(id).enqueueFirst(rec);
                    bumpUiRevision(id);
                    rec = null;
                }
            }
            if (rec != null) {
                rec.setState(TaskState.RUNNING);
                rec.clearFailure();
                rec.setInventoryBefore(TaskEvidence.inventoryTotals(player));
                rec.markUiStarted(player.level.getGameTime(), TaskUiDetails.current(rec, player.level.getGameTime()));
                bumpUiRevision(id);
                try {
                    CompanionTask task = CompanionTaskFactory.create(player, rec);
                    TaskProgressWatchdog watchdog = new TaskProgressWatchdog();
                    watchdog.start(player.position(), player.level.getGameTime());
                    running = new Running(task, rec, watchdog);
                    ACTIVE.put(id, running);
                    task.start();   // may flip the record terminal immediately
                } catch (RuntimeException ex) {
                    com.dwinovo.numen.core.Constants.LOG.warn("Failed to start restored task {} ({})",
                            rec.getToolCallId(), rec.getToolName(), ex);
                    rec.markFailure(TaskFailureCode.INTERNAL_ERROR, ex.toString());
                    rec.setResult(TaskResult.fail("task could not be restored: " + ex.getMessage(),
                            TaskFailureCode.INTERNAL_ERROR.code(), Map.of("tool", rec.getToolName())));
                    rec.setState(TaskState.FAILED);
                    rememberResult(new TaskKey(id, rec.getToolCallId()), rec.getResult().toJson());
                    queueFor(id).complete(rec);
                    CompanionWorkCoordinator.release(id, rec.getToolCallId());
                }
            }
        } else if (running.record().getState() == TaskState.RUNNING) {
            long now = player.level.getGameTime();
            java.util.Optional<String> leaseBlock = CompanionWorkCoordinator.tryAcquire(player, running.record());
            if (leaseBlock.isPresent()) {
                running.record().setUiBlocker(leaseBlock.get());
                com.dwinovo.numen.core.pathing.exec.InputDriver.halt(player);
                pushTaskSnapshot(player, false);
                return;
            }
            running.record().setUiBlocker("");
            if (running.task().monitorsMovementProgress()) {
                running.watchdog().recordPosition(player.position(), now);
            }
            if (now >= running.record().getDeadlineGameTime()) {
                running.record().markFailure(TaskFailureCode.TIMEOUT, "deadline reached");
                running.record().setState(TaskState.TIMEOUT);
            } else if (running.task().monitorsMovementProgress() && running.watchdog().isStuck(now)) {
                if (running.watchdog().canRecover() && running.task().recoverFromStuck()) {
                    int attempts = running.watchdog().markRecovery(player.position(), now);
                    running.record().setRecoveryAttempts(attempts);
                    running.record().markFailure(TaskFailureCode.STUCK,
                            "no movement progress; recovered attempt " + attempts);
                    com.dwinovo.numen.core.Constants.LOG.debug(
                            "Recovered stuck task {} ({}) for companion {} attempt {}",
                            running.record().getId(), running.record().getToolName(), id, attempts);
                } else {
                    running.record().markFailure(TaskFailureCode.STUCK,
                            "no movement progress for " + running.watchdog().idleTicks(now) + " ticks");
                    running.record().setState(TaskState.FAILED);
                }
            } else {
                try {
                    running.record().setState(running.task().tick());
                } catch (RuntimeException ex) {
                    com.dwinovo.numen.core.Constants.LOG.warn("Task {} ({}) threw during tick",
                            running.record().getToolCallId(), running.record().getToolName(), ex);
                    running.record().markFailure(TaskFailureCode.INTERNAL_ERROR, ex.toString());
                    running.record().setState(TaskState.FAILED);
                }
            }
        }

        // Finish on any terminal state (set by start(), tick(), deadline, stuck, or cancel).
        running = ACTIVE.get(id);
        if (running != null) {
            TaskState st = running.record().getState();
            if (st != TaskState.RUNNING && st != TaskState.PENDING && st != TaskState.PAUSED) {
                if (st == TaskState.CANCELLED && running.record().getFailureCode() == null) {
                    running.record().markFailure(TaskFailureCode.CANCELLED, "interrupted by owner");
                }
                try {
                    running.record().setResult(running.task().buildResult(st));
                } catch (RuntimeException ex) {
                    com.dwinovo.numen.core.Constants.LOG.warn("Task {} ({}) failed while building result",
                            running.record().getToolCallId(), running.record().getToolName(), ex);
                    running.record().markFailure(TaskFailureCode.INTERNAL_ERROR, ex.toString());
                    running.record().setResult(TaskResult.fail("task failed during recovery cleanup: " + ex.getMessage(),
                            TaskFailureCode.INTERNAL_ERROR.code(), Map.of("tool", running.record().getToolName())));
                }
                TaskResult result = TaskEvidence.decorate(player, running.record(), running.record().getResult());
                running.record().setResult(result);
                rememberResult(new TaskKey(id, running.record().getToolCallId()),
                        result == null ? "{\"success\":false,\"message\":\"no result produced\"}" : result.toJson());
                queueFor(id).complete(running.record());
                CompanionWorkCoordinator.release(id, running.record().getToolCallId());
                ACTIVE.remove(id);
                bumpUiRevision(id);
                persistNow(player.level.getServer());
            }
        }

        drainResults(player);
        pushTaskSnapshot(player, false);
    }

    private static void persistPeriodically(MinecraftServer server) {
        if (server == null) return;
        long now = server.overworld().getGameTime();
        boolean activeCheckpointDue = !ACTIVE.isEmpty()
                && (lastPersistGameTime == Long.MIN_VALUE || now - lastPersistGameTime >= ACTIVE_CHECKPOINT_TICKS);
        if (persistenceDirty || activeCheckpointDue) {
            persistNow(server);
        }
    }

    public static void persistNow(MinecraftServer server) {
        if (server == null) return;
        ensureLoaded(server);
        lastPersistGameTime = server.overworld().getGameTime();
        TaskStateStore.save(server, snapshots(lastPersistGameTime), COMPLETED_RESULTS);
        persistenceDirty = false;
    }

    public static List<TaskQueueSnapshot> snapshots(long gameTime) {
        java.util.ArrayList<TaskQueueSnapshot> out = new java.util.ArrayList<>();
        java.util.HashSet<UUID> ids = new java.util.HashSet<>();
        ids.addAll(QUEUES.keySet());
        ids.addAll(ACTIVE.keySet());
        ids.addAll(PAUSED_AT.keySet());
        for (UUID id : ids) {
            TaskQueue q = QUEUES.get(id);
            Running running = ACTIVE.get(id);
            TaskRecord active = running == null ? null : running.record();
            List<TaskRecord> pending = q == null ? List.of() : q.pendingSnapshot();
            if (active == null && pending.isEmpty() && !PAUSED_AT.containsKey(id)) continue;
            out.add(new TaskQueueSnapshot(
                    id,
                    PAUSED_AT.containsKey(id),
                    active,
                    pending,
                    gameTime,
                    PAUSED_AT.getOrDefault(id, gameTime)));
        }
        return out;
    }

    private static void drainResults(NumenPlayer player) {
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;   // keep the outbox until the owner reconnects
        List<TaskRecord> completed = queueFor(player.getUUID()).drainCompleted();
        if (completed.isEmpty()) return;
        for (TaskRecord rec : completed) {
            TaskResult result = rec.getResult();
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            rememberResult(new TaskKey(player.getUUID(), rec.getToolCallId()), json);
            Services.NETWORK.sendToPlayer(owner, TaskResultPayload.ID,
                    new TaskResultPayload(player.getUUID(), rec.getToolCallId(), json));
        }
    }

    private static void rememberResult(TaskKey key, String json) {
        COMPLETED_RESULTS.put(key, json);
        trimCompletedResults();
        persistenceDirty = true;
    }

    private static void pushTaskSnapshot(NumenPlayer player, boolean force) {
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;
        UUID id = player.getUUID();
        long now = player.level.getGameTime();
        if (!force && now - LAST_UI_PUSH_AT.getOrDefault(id, Long.MIN_VALUE / 2) < 10L) return;
        List<com.dwinovo.numen.network.payload.TaskListPayload.Entry> entries = uiTasks(id);
        int signature = java.util.Objects.hash(PAUSED_AT.containsKey(id), INVENTORY_LOCKED_AT.containsKey(id), entries);
        if (!force && signature == LAST_UI_SIGNATURES.getOrDefault(id, Integer.MIN_VALUE)) return;
        LAST_UI_SIGNATURES.put(id, signature);
        LAST_UI_PUSH_AT.put(id, now);
        bumpUiRevision(id);
        Services.NETWORK.sendToPlayer(owner, com.dwinovo.numen.network.payload.TaskListPayload.ID,
                new com.dwinovo.numen.network.payload.TaskListPayload(id, uiRevision(id),
                        PAUSED_AT.containsKey(id), INVENTORY_LOCKED_AT.containsKey(id), entries));
    }

    private static void trimCompletedResults() {
        while (COMPLETED_RESULTS.size() > MAX_COMPLETED_RESULTS) {
            TaskKey oldest = COMPLETED_RESULTS.keySet().iterator().next();
            COMPLETED_RESULTS.remove(oldest);
        }
    }
}
