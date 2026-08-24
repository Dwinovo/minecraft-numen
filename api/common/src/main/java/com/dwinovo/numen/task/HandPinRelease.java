package com.dwinovo.numen.task;

/**
 * The task-scoped release edge for the MAINHAND pin (constitution §5 /
 * spec point: 任务结束边沿解除手持钉), as a pure tick counter so it is
 * headless-testable.
 *
 * <p>Why not release on the bare {@code hasWork() true→false} edge: the client
 * ToolDispatcher is strictly serial — between two tool calls of the SAME turn
 * (equip_item result shipped, auto_mine not yet arrived) the LLM chain is
 * momentarily idle while the model thinks, and a bare-edge release would strip
 * the pin exactly between "equip the fast-breaking tool" and "mine with it",
 * defeating the pin's whole purpose. So the completion edge is debounced: the
 * release fires once the chain has stayed idle for a grace window that
 * comfortably covers inter-call model latency. The CANCEL edge (owner Stop) and
 * death stay immediate — those are handled at their call sites.
 */
public final class HandPinRelease {

    private final int graceTicks;
    private int idleTicks;
    private boolean fired;

    public HandPinRelease(int graceTicks) {
        this.graceTicks = graceTicks;
    }

    /**
     * Feed one tick of the LLM chain's busy state; returns {@code true} exactly
     * once per work session, {@code graceTicks} after the chain last had work.
     * New work re-arms the edge.
     */
    public boolean tick(boolean llmBusy) {
        if (llmBusy) {
            idleTicks = 0;
            fired = false;
            return false;
        }
        if (fired) return false;
        if (++idleTicks >= graceTicks) {
            fired = true;
            return true;
        }
        return false;
    }
}
