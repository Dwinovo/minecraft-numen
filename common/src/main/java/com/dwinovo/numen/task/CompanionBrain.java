package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * One companion body's scheduler — the per-UUID value {@code CompanionTickDispatcher}
 * keeps. Replaces the old two static maps (a {@code TaskQueue} + a single
 * {@code Running} task) with an ordered chain list; each server tick it ticks ONLY
 * the highest-priority active chain (priority arbitration: every chain bids, one
 * winner drives the body), then always drains completed LLM results.
 *
 * <p>Chain roster: 内容包经 {@link BrainChains} 注册(numen-core 注册五条生存
 * 本能),引擎自带的 {@link LlmTaskChain} 与说话看人姿态链固定压轴。A
 * survival spike preempts the LLM task (its body is released via
 * {@link LlmTaskChain#onInterrupt}, its deadline frozen via
 * {@link LlmTaskChain#freezeTick}) and it resumes when the spike subsides.
 *
 * <p>This class also wires the {@link BodyLog} (constitution §4, 即报即发):
 * chains report body episodes into the log, whose transport is this brain's
 * {@link #tryEmitEvent} — one {@code <event kind="body_log">} to the owner's
 * client; consumption timing is the client inbox's three-state routing.
 */
final class CompanionBrain {

    /**
     * Grace window before the MAINHAND intent pin auto-releases once the LLM
     * chain runs out of work (the 任务结束 edge, constitution §5 / point 11).
     * Debounced instead of a bare edge because the client dispatches tool calls
     * strictly serially — between two calls of one turn the chain is idle for
     * however long the model thinks; 30s comfortably outlives that gap while
     * still clearing a stale hand pin soon after the job truly ends.
     */
    private static final int HAND_PIN_GRACE_TICKS = 600;

    final TaskQueue queue = new TaskQueue();
    /** The body's narrative outlet: 即报即发,消费时机由客户端收件箱三态路由。
     *  See {@link BodyLog}. */
    private final BodyLog bodyLog;
    final LlmTaskChain llm;

    private final List<TaskChain> chains;

    /** The body this brain is currently acting for — bound at every entry point
     *  that can trigger a body-log flush, read by {@link #tryEmitEvent}. (The
     *  brain is keyed per companion UUID, but chains report without a companion
     *  argument, so the flush transport resolves the body through this field.) */
    private NumenPlayer body;

    /** Last tick's winner, so we can fire {@code onInterrupt} exactly on the switching edge. */
    private TaskChain running;

    /** Task-idle edge for the hand pin (pure counter; see {@link #HAND_PIN_GRACE_TICKS}). */
    private final com.dwinovo.numen.task.HandPinRelease handPinRelease =
            new com.dwinovo.numen.task.HandPinRelease(HAND_PIN_GRACE_TICKS);

    CompanionBrain() {
        this.bodyLog = new BodyLog(this::tryEmitEvent);
        this.llm = new LlmTaskChain(queue);
        List<TaskChain> all = new ArrayList<>(BrainChains.build(bodyLog));
        all.add(llm);
        all.add(new com.dwinovo.numen.task.chain.SpeakingLookChain());
        this.chains = List.copyOf(all);
    }

    /**
     * {@link BodyLog}'s transport: ship the packaged {@code body_log} event to
     * the owner's client via the engine's public event channel. principal=false
     * ——身体叙事是事实,事实不配自定紧急度;消费时机由客户端收件箱按发生时
     * 状态路由(任务中=军情立刻开轮,全闲=躺着搭车)。No owner online →
     * refuse, so the log keeps its entries and retries on a later flush.
     */
    private boolean tryEmitEvent(String xml) {
        NumenPlayer companion = body;
        if (companion == null || companion.resolveOwnerPlayer() == null) return false;
        com.dwinovo.numen.entity.Companions.emitEvent(companion, xml, false);
        return true;
    }

    void tick(NumenPlayer companion) {
        body = companion;

        // 任务结束边沿 (constitution §5): the LLM chain has stayed workless past the
        // grace window — the explicit-hold session is over, the hand goes back to
        // the reflexes. Armor pins are untouched (their life is §5's four natural
        // endpoints); only MAINHAND is task-scoped.
        if (handPinRelease.tick(llm.hasWork())) {
            TaskSessionHooks.fireSessionEnd(companion);
        }

        TaskChain best = ChainScheduler.select(chains, companion);

        if (best == null) {
            // Everything dormant (idle body). Release whoever held control, then
            // finalize + drain — a record cancelled out-of-band must still ship.
            if (running != null) {
                running.onInterrupt(companion);
                running = null;
            }
            // Idle retry for entries a refused flush left behind (the owner was
            // offline when they were reported) — a no-op when the log is empty.
            bodyLog.flush();
            llm.finalizeTerminal();
            llm.drainResults(companion);
            return;
        }

        if (running != null && running != best) {
            running.onInterrupt(companion);
        }
        running = best;

        // A non-LLM (survival) chain holds the body this tick → the paused LLM task
        // must not burn its deadline.
        if (best != llm) {
            llm.freezeTick(companion);
        }

        best.tick(companion);

        // Finalize EVERY tick, not just when llm wins: an owner Stop (cancelFor)
        // marks the record terminal out-of-band, and the client's serial
        // ToolDispatcher is wedged until that single result ships — even while a
        // survival chain holds the body. (A no-op when llm.tick already finalized.)
        llm.finalizeTerminal();
        llm.drainResults(companion);
    }

    /**
     * Death path (via {@code CompanionTickDispatcher.clearActiveTask}): bind the
     * body so the sink can reach its owner, drop the running task, and retry any
     * body-log entries a refused flush left behind (owner was offline).
     */
    void dropActiveNoResult(NumenPlayer companion) {
        body = companion;
        // Death ends the task session — the task-scoped hand pin goes with it.
        TaskSessionHooks.fireSessionEnd(companion);
        llm.dropActiveNoResult();
        bodyLog.flush();
    }
}
