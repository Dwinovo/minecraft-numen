package com.dwinovo.numen.task.base;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;

/**
 * A cheap, side-effect-free "can this task even begin?" gate, checked once before any body is driven: by
 * {@link AbstractCompanionTask#prepare} before the work is accepted (a failure is the call's error result, with no
 * task id), or by {@link AbstractCompanionTask#start} when nothing prepared the task (a synchronous action, a child
 * sub-goal).
 *
 * <p>Preconditions replace the ad-hoc fail-fast blocks each concrete task used to
 * open with (e.g. {@code BuildCompanionTask} rejecting an occupied target with
 * replacement off, {@code DigCompanionTask} rejecting an un-harvestable
 * request). Expressing them as a small ordered list keeps the "why can't I start"
 * diagnosis uniform: the FIRST precondition that reports a {@link Failure} decides
 * the refusal (or the task's terminal result), carrying both a model-facing message and a
 * {@link FailureType} the reactive layer can branch on.
 *
 * <p>A precondition is a PREREQUISITE check — the kinds of failure it emits
 * ({@link FailureType#NO_MATERIAL}, {@link FailureType#WRONG_TOOL}, …) are exactly
 * the "kick back to the LLM" categories: the deterministic layer must not silently
 * acquire what's missing, it reports and stops.
 */
public interface Precondition {

    /**
     * Evaluate the gate.
     *
     * @return {@code null} when satisfied (the task may start); otherwise the
     *         {@link Failure} to report as the task's terminal result.
     */
    Failure check();

    /** 走路的一趟从脚下起走:坐着任何载具都先下来({@link #sitting})。 */
    static Precondition onFoot(NumenPlayer player) {
        return () -> player.getVehicle() == null ? null : sitting(player, null);
    }

    /**
     * 坐着一件这一趟驾驭不了的载具:走路的一趟从不自己下车,下车是她的决定,下一步是 {@code numen.use.dismount()}。
     *
     * @param why 为什么驾驭不了,接在"我坐在 X 里"后面;null 是"走路驾驭不了它"
     */
    static Failure sitting(NumenPlayer player, String why) {
        String what = BuiltInRegistries.ENTITY_TYPE.getKey(player.getVehicle().getType()).getPath();
        return new Failure("I am sitting in the " + what + (why == null ? ", which a walk can't steer" : ", " + why)
                + ": `numen.use.dismount()` steps off, then I go on from the ground", FailureType.UNKNOWN,
                "numen.use.dismount()");
    }

    /**
     * A precondition's verdict when it is NOT satisfied.
     *
     * @param hint 能照抄的下一步(走过去的那一行);没有为 null
     */
    record Failure(String message, FailureType type, String hint) {

        public Failure(String message, FailureType type) {
            this(message, type, null);
        }
    }
}
