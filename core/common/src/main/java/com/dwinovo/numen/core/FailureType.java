package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.task.TaskState;

/**
 * Structured "why did it fail" category, threaded up out of the pathing/placement
 * substrate ({@code PlayerNav}, {@code Interaction},
 * {@code BlockDigger}) so the reactive task layer can BRANCH on the cause instead
 * of string-matching a human-readable reason.
 *
 * <p>This is distinct from {@link TaskState}: {@code TaskState} is the task's
 * lifecycle (running / terminal); {@code FailureType} is the diagnosis attached to
 * a {@code FAILED}. A {@code failReason} String still rides alongside for the LLM's
 * benefit — the enum is for code, the string is for the model.
 *
 * <h2>Recovery boundary (why the taxonomy is shaped this way)</h2>
 * The governing rule of the reactive layer is that a recovery ladder recovers the
 * <em>execution of one bounded goal</em>; it never expands the goal's scope or
 * auto-acquires a prerequisite. The categories therefore split into two kinds:
 * <ul>
 *   <li><b>In-ladder</b> (a different way to reach the SAME bounded goal is worth
 *       trying): {@link #OCCLUDED}, {@link #BOXED_IN}, {@link #NO_PATH},
 *       {@link #OUT_OF_REACH}, {@link #HAZARD}.</li>
 *   <li><b>Kick-back-to-LLM</b> (the goal can't be met without a strategic
 *       decision the deterministic layer must not make): {@link #NO_MATERIAL},
 *       {@link #WRONG_TOOL}, {@link #TARGET_LOST}, {@link #MINED_OUT} — the model
 *       decides whether to acquire the missing thing, widen the search, or stop.</li>
 * </ul>
 * A rung declares which {@code FailureType}s it {@code handles}; anything it does
 * not handle falls straight through to a terminal give-up carrying this cause.
 */
public enum FailureType {
    /** Out of blocks/items in inventory to place or use. Prerequisite — kick to LLM. */
    NO_MATERIAL,
    /** No free inventory slot to stow into (unequipped gear, picked-up loot). Kick to
     *  LLM: dropping or depositing something first is a strategic call. */
    NO_SPACE,
    /** Nothing solid to place against at/around the target, or nothing vanilla lets the block stand on there (a
     *  flower on stone, a torch on air): the block will not hold at that spot. In-ladder when another support face
     *  exists; when the spot itself cannot hold the block, kick to LLM — only changing the plan or the ground helps. */
    NO_SUPPORT,
    /** A living/building-blocking entity occupies the target cell — vanilla refuses every
     *  press until it moves. Kick to LLM: waiting, luring it away, or picking another cell
     *  is a strategic call; no stance change or dig fixes it. */
    ENTITY_BLOCKED,
    /** No line of sight to the face/block — the view is boxed in by a wall/occluder. In-ladder. */
    OCCLUDED,
    /** The BODY itself can't move out / no path survived the replan budget. In-ladder. */
    BOXED_IN,
    /** A* returned nothing to the target. In-ladder: try a looser goal (near/adjacent). */
    NO_PATH,
    /** No route within what the walk may change: none without altering terrain while a digging /
     *  bridging / pillaring one exists, or the way on needs cells beyond the plan she agreed to.
     *  The reason says how many blocks it would take and what in the walk's description allows it
     *  ({@code costs = {dig = true, place = true}}, then {@code numen.route.plan} again). Approach tasks
     *  treat it in-ladder like NO_PATH (a looser stance may still avoid it); a route walk does not
     *  loosen anything on it — whether to allow more is the LLM's call. */
    TERRAIN_BLOCKED,
    /** Never got within interaction reach of the target. In-ladder: reposition. */
    OUT_OF_REACH,
    /** Can't harvest/attack effectively with the current inventory. Prerequisite — kick to LLM. */
    WRONG_TOOL,
    /** The entity/block target is gone, dead, or moved out of the bounded search. Kick to LLM. */
    TARGET_LOST,
    /** No more matching targets within the bounded scan radius. Kick to LLM (widen? stop?). */
    MINED_OUT,
    /** The permission layer refused the action (owner's rule, observe mode, or a consent that was
     *  not given). Kick to LLM: the model must not route around it — the owner decides. */
    REFUSED,
    /** The block went in but did not stay, or the world would not take it: another mod cancelled the placement, the
     *  game refused the write, or something outside the job kept breaking the finished work. Not the permission
     *  layer ({@link #REFUSED}), not a spot that cannot hold it ({@link #NO_SUPPORT}), not a body in the way
     *  ({@link #ENTITY_BLOCKED}) — each of those has its own remedy. Kick to LLM: trying again the same way changes
     *  nothing; what keeps undoing it has to be found first. */
    NOT_KEPT,
    /** A fluid/lava/void hazard blocks the safe execution. In-ladder: route around, else give up. */
    HAZARD,
    /** Pre-empted or cancelled (owner stop, death). Not a real failure — terminal housekeeping. */
    INTERRUPTED,
    /** Ran out of deadline budget. */
    TIMED_OUT,
    /** The record type had no registered runner. */
    UNSUPPORTED,
    /**
     * 任务自己抛了异常——<b>我们的 bug,不是世界的问题</b>。
     *
     * <p>单列一档而不是并进 {@link #UNKNOWN}:那一档的意思是"原因没归类",而这一档的
     * 意思是"这里本不该发生"。两者对模型的含义完全不同——前者可以换个法子再试,后者
     * 再试多少遍都一样,而且该被人看见。
     */
    INTERNAL,
    /** Cause not classified. */
    UNKNOWN;

    /**
     * 这一类失败交给脚本时是哪一种错误值({@link ErrorKind}):脚本按它分支,所以只分到她下一步做法不同的那几种——路不通、
     * 够不着、被拒、东西没了、缺料、被叫停、超时;其余是 {@link ErrorKind#FAILED}。
     */
    public ErrorKind kind() {
        return switch (this) {
            case NO_PATH, BOXED_IN, TERRAIN_BLOCKED, HAZARD -> ErrorKind.NO_PATH;
            case OUT_OF_REACH, OCCLUDED -> ErrorKind.OUT_OF_REACH;
            case REFUSED -> ErrorKind.DENIED;
            case TARGET_LOST, MINED_OUT -> ErrorKind.NOT_FOUND;
            case NO_MATERIAL -> ErrorKind.NO_MATERIAL;
            case INTERRUPTED -> ErrorKind.INTERRUPTED;
            case TIMED_OUT -> ErrorKind.TIMEOUT;
            case NO_SPACE, NO_SUPPORT, ENTITY_BLOCKED, NOT_KEPT, WRONG_TOOL, UNSUPPORTED, INTERNAL, UNKNOWN ->
                    ErrorKind.FAILED;
        };
    }
}
