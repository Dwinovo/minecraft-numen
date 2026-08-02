package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.CompanionChunkLoader;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * numen-core's server-tick driver of companion tasks. The engine ({@code numen-api})
 * owns the body and is a pure scheduler; <em>task execution</em> is core's, so the
 * per-companion scheduler ({@link CompanionBrain}) lives here (keyed by companion
 * UUID), not on the body.
 *
 * <p>Each tick, for every live {@link NumenPlayer}, the brain ticks the
 * highest-priority active chain and ships finished LLM results back to the owner
 * as {@code TaskResultPayload}. Registered from core's end-of-tick hooks;
 * finalised on body removal / death / owner-abort via the engine's
 * {@code CompanionLifecycle} seam.
 *
 * <p>This class is a thin, signature-preserving facade over {@link CompanionBrain}
 * — {@link #queueFor} and the three lifecycle finalizers are the API that tools
 * ({@code TaskDispatch.enqueue}, {@code WaitTool}) and the lifecycle wiring
 * depend on, so they are unchanged; the multi-chain scheduling all lives in the
 * brain.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionTickDispatcher {

    private static final Map<UUID, CompanionBrain> BRAINS = new HashMap<>();

    private CompanionTickDispatcher() {}

    private static CompanionBrain brainFor(UUID companionUuid) {
        return BRAINS.computeIfAbsent(companionUuid, k -> new CompanionBrain());
    }

    /** The companion's task queue (created on first use). Body-bound tools enqueue here. */
    public static TaskQueue queueFor(UUID companionUuid) {
        return brainFor(companionUuid).queue;
    }

    /** 一次性心跳日志:证明排程机器的 tick 钩子真的接上了(排查"闲时链不触发"时先看它)。 */
    private static boolean heartbeatLogged;

    public static void tick(MinecraftServer server) {
        if (!heartbeatLogged) {
            heartbeatLogged = true;
            com.dwinovo.numen.Constants.LOG.info("[numen-task] scheduler heartbeat online (first server tick)");
        }
        Companions.tickRespawns(server);   // timed death recoveries
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer ap) {
                // Re-stamp the companion's loading pad from the SERVER tick (this runs every tick over
                // the player list, unconditionally) — NOT from NumenPlayer.tick(), which the entity
                // system only calls while the companion's chunk is already entity-ticking. Doing it here
                // breaks the chicken-and-egg: a companion whose chunk fell out of ticking range still gets
                // its pad refreshed, which pulls its chunk back into range so it resumes ticking. Gated on
                // the owner being online — an owner-less companion (owner logged off on a server) shouldn't
                // hold chunks loaded; its pad lapses and it idles until the owner returns. In single-player
                // the owner is always online while the world runs, so this never gates there. See
                // CompanionChunkLoader.
                UUID owner = ap.getOwnerUuid();
                if (owner != null && server.getPlayerList().getPlayer(owner) != null) {
                    CompanionChunkLoader.refresh(ap);
                }
                brainFor(ap.getUUID()).tick(ap);
            }
        }
    }

    /**
     * Drop a companion's running task WITHOUT shipping a result — used on death, where the client's
     * {@code NumenDeathPayload} already resolves the in-flight tool call with the death cause (so a
     * second result here would be a duplicate the client ignores). The brain also fallback-flushes
     * its {@link BodyLog} here: entries queued for that result have no D1 tail anymore.
     */
    public static void clearActiveTask(NumenPlayer player) {
        CompanionBrain brain = BRAINS.get(player.getUUID());   // never create: a late death
        if (brain != null) brain.dropActiveNoResult(player);   // event must not leak a brain
    }

    /** 身体当前的异步任务(运行中或已受理待跑),null = 没有。task_status/派发闸门用。 */
    public static TaskRecord asyncTaskFor(UUID companionUuid) {
        CompanionBrain brain = BRAINS.get(companionUuid);
        return brain == null ? null : brain.llm.asyncRecord();
    }

    /** LLM 车道是否有任何工作(运行或排队,同步异步都算)。异步受理的占用判定用。 */
    public static boolean llmLaneBusy(UUID companionUuid) {
        CompanionBrain brain = BRAINS.get(companionUuid);
        return brain != null && brain.llm.hasWork();
    }

    /**
     * task_stop:LLM 主动叫停当前异步任务——与主人 Stop 同一条取消路(含 MAINHAND
     * 意图钉释放),原因词不同。返回被叫停的记录,null = 本来就没有异步任务在跑。
     * 收尾结果由 drainResults 以 task_finished(status=stopped) 事件送达。
     */
    public static TaskRecord stopActive(NumenPlayer player, String reason) {
        CompanionBrain brain = BRAINS.get(player.getUUID());
        if (brain == null) return null;
        TaskRecord target = brain.llm.asyncRecord();
        if (target == null) return null;
        brain.queue.cancelAll(reason);
        brain.llm.cancelActive();
        TaskSessionHooks.fireSessionEnd(player);
        return target;
    }

    /** Owner pressed Stop: cancel the pending queue and the running task (finalized next tick).
     *  The 取消边沿 also releases the task-scoped MAINHAND intent pin immediately —
     *  the explicit-hold session dies with the task it served (constitution §5). */
    public static void cancelFor(NumenPlayer player) {
        CompanionBrain brain = BRAINS.get(player.getUUID());   // never create: a late cancel
        if (brain == null) return;                             // packet must not leak a brain
        brain.queue.cancelAll("interrupted by owner");
        brain.llm.cancelActive();
        TaskSessionHooks.fireSessionEnd(player);
    }

    /**
     * Finalize a companion's running task because the BODY is leaving the world
     * (dormancy / dismissal / death) — the tick loop only visits players still in
     * the player list, so without this the running task is orphaned and its
     * {@code buildResult} side-effects never run (e.g. a mining dig's crack overlay
     * would stay painted on every viewer until chunk reload).
     */
    public static void onCompanionRemoved(NumenPlayer player) {
        UUID id = player.getUUID();
        CompanionBrain brain = BRAINS.get(id);
        if (brain != null) {
            brain.llm.finalizeActive();
            brain.llm.drainResults(player);
        }
        BRAINS.remove(id);   // the body is gone; don't leak its brain
    }
}
