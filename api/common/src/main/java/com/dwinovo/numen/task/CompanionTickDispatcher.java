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

    /** 已经为"同 UUID 两具身体"警告过的 —— 每具同伴只吵一次。 */
    private static final java.util.Set<UUID> duplicateWarned = new java.util.HashSet<>();

    static {
        // 大脑属于世界,不属于进程:退出存档就把整表作废,下一个存档从空表开始。
        // 见 ServerLifecycle —— 少了这一步,上一局的任务会绑着上一局的身体在新世界里跑。
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(CompanionTickDispatcher::dropAll);
    }

    private CompanionTickDispatcher() {}

    /**
     * 世界没了,大脑跟着没。心跳标记一并复位——它证明的是"这个服务器的 tick 钩子接上了",
     * 每个服务器都该重新证一次(它正是当初暴露这个 bug 的那把尺子)。
     */
    static void dropAll() {
        BRAINS.clear();
        duplicateWarned.clear();
        heartbeatLogged = false;
    }

    private static CompanionBrain brainFor(UUID companionUuid) {
        return BRAINS.computeIfAbsent(companionUuid, k -> new CompanionBrain());
    }

    /** 回合挂着等的同步动作槽(首次使用时建脑)。 */
    static TaskSlot syncSlotFor(UUID companionUuid) {
        return brainFor(companionUuid).sync;
    }

    /** 她现在在做的事那个槽(首次使用时建脑)。 */
    static TaskSlot currentSlotFor(UUID companionUuid) {
        return brainFor(companionUuid).current;
    }

    /** 一次性心跳日志:证明排程机器的 tick 钩子真的接上了(排查"闲时链不触发"时先看它)。 */
    private static boolean heartbeatLogged;

    public static void tick(MinecraftServer server) {
        if (!heartbeatLogged) {
            heartbeatLogged = true;
            com.dwinovo.numen.Constants.LOG.info("[numen-task] scheduler heartbeat online (first server tick)");
        }
        Companions.tickRespawns(server);   // timed death recoveries
        TimerRegistry.tick(server);        // 她自己定的表,到点发事件
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
                // 背包变了就推给主人一份。变化由原版的 ContainerListener 报,这里每 tick
                // 只有一次引用比较(菜单换没换),见 CompanionInventoryWatch。
                com.dwinovo.numen.entity.CompanionInventoryWatch.tick(ap, server.getTickCount());
                // 她从床上醒了就开一轮:sleep 到躺下就返回,醒来这一刻没有别的东西说得出。
                if (ap.pollWokeUp()) {
                    Companions.onWoke(ap);
                }
                // 饿了说一声。她不会自己吃 —— 那条常驻链删了,交互本身就是目的。
                if (ap.pollGotHungry()) {
                    com.dwinovo.numen.event.NumenEvents.gotHungry(
                            ap, ap.getFoodData().getFoodLevel());
                }
                CompanionBrain brain = brainFor(ap.getUUID());
                if (!brain.boundTo(ap) && !brain.boundBodyGone()) {
                    // 同一个 UUID 同时有两具身体:上一具还在世界里,来的这具是重影。
                    // 【不拆不建】—— 拆建会在两具之间无限自旋,每刻两次,每次还重放
                    // 一遍她手上的活。这里只吵一句就跳过;病根在"她被复活了两次",
                    // 见 ExecuteToolPayload。
                    if (duplicateWarned.add(ap.getUUID())) {
                        com.dwinovo.numen.Constants.LOG.warn(
                                "[numen-task] {} 同时有两具身体在玩家列表里,忽略后来的那具", ap.getUUID());
                    }
                    continue;
                }
                if (!brain.boundTo(ap)) {
                    // 到不了这里（关服已经清过表）—— 到了就是哪处漏了，大声记一笔。
                    // 整个大脑作废：里面的任务都绑在旧身体上，一个都不能再 tick。
                    com.dwinovo.numen.Constants.LOG.warn(
                            "[numen-task] {} 换了身体但旧大脑还在，整个作废重建", ap.getUUID());
                    BRAINS.remove(ap.getUUID());
                    brain = brainFor(ap.getUUID());
                }
                if (!brain.restored) {
                    // 首次见到这具身体:把重启前她手上的活接回来(见 TaskPersistence)。
                    brain.restored = true;
                    TaskPersistence.restore(ap);
                }
                brain.tick(ap);
            }
        }
    }

    /**
     * Drop a companion's running task WITHOUT shipping a result — used on death, where the client's
     * {@code NumenDeathPayload} already resolves the in-flight tool call with the death cause (so a
     * second result here would be a duplicate the client ignores).
     */
    public static void clearActiveTask(NumenPlayer player) {
        CompanionBrain brain = BRAINS.get(player.getUUID());   // never create: a late death
        if (brain != null) brain.dropActiveNoResult(player);   // event must not leak a brain
    }

    /** 她现在在做的那件事,null = 槽空(她站着)。task_status 用。 */
    public static TaskRecord currentTaskFor(UUID companionUuid) {
        CompanionBrain brain = BRAINS.get(companionUuid);
        return brain == null ? null : brain.current.record();
    }

    /**
     * 槽里那个刚受理、一刻都还没跑过。
     *
     * <p>用来分开两种"再派一个活":同一批工具调用里的第二个(模型在做计划,该拒绝
     * ——让它拿到第一个的结果再决定下一步),和新回合里派的(主人/模型改主意了,
     * 该直接替换)。判据本地可判,不用把回合 id 穿到服务端。
     */
    public static boolean currentFreshlyAccepted(NumenPlayer companion) {
        CompanionBrain brain = BRAINS.get(companion.getUUID());
        return brain != null && brain.current.freshlyAccepted(companion);
    }

    /**
     * task_stop:LLM 主动叫停当前异步任务——与主人 Stop 同一条取消路(含 MAINHAND
     * 意图钉释放),原因词不同。返回被叫停的记录,null = 本来就没有异步任务在跑。
     * 收尾结果由 drainResults 以 task_finished(status=stopped) 事件送达。
     */
    public static TaskRecord stopActive(NumenPlayer player, String reason) {
        CompanionBrain brain = BRAINS.get(player.getUUID());
        if (brain == null) return null;
        TaskRecord target = brain.current.record();
        if (target == null) return null;
        brain.current.cancel();
        TaskSessionHooks.fireSessionEnd(player);
        return target;
    }

    /** Owner pressed Stop: cancel the pending queue and the running task (finalized next tick).
     *  The 取消边沿 also releases the task-scoped MAINHAND intent pin immediately —
     *  the explicit-hold session dies with the task it served (constitution §5). */
    public static void cancelFor(NumenPlayer player) {
        CompanionBrain brain = BRAINS.get(player.getUUID());   // never create: a late cancel
        if (brain == null) return;                             // packet must not leak a brain
        brain.sync.cancel();
        brain.current.cancel();
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
            brain.finalizeActive(player);
        }
        BRAINS.remove(id);   // the body is gone; don't leak its brain
    }
}
