package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

/**
 * Structured "why did it fail" category, threaded up out of the pathing/placement
 * substrate ({@code PlayerNav}, {@code PlaceManeuver}, {@code Interaction},
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
    /** Nothing solid to place against at/around the target. In-ladder: try another support face. */
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
    /** Never got within interaction reach of the target. In-ladder: reposition. */
    OUT_OF_REACH,
    /** The search's goal membership IS satisfied at the feet, but the task's richer
     *  arrival (reach / line of sight / on-ground) still isn't — the stance the graph
     *  chose is a dud for the actual work. In-ladder: reposition, or blacklist the
     *  composite member that produced it. */
    STANCE_DUD,
    /** Can't harvest/attack effectively with the current inventory. Prerequisite — kick to LLM. */
    WRONG_TOOL,
    /** The entity/block target is gone, dead, or moved out of the bounded search. Kick to LLM. */
    TARGET_LOST,
    /** No more matching targets within the bounded scan radius. Kick to LLM (widen? stop?). */
    MINED_OUT,
    /** A fluid/lava/void hazard blocks the safe execution. In-ladder: route around, else give up. */
    HAZARD,
    /** Pre-empted or cancelled (owner stop, death). Not a real failure — terminal housekeeping. */
    INTERRUPTED,
    /** Ran out of deadline budget. */
    TIMED_OUT,
    /** The record type had no registered runner. */
    UNSUPPORTED,
    /** Cause not classified. */
    UNKNOWN;
}
