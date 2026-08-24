package com.dwinovo.numen.task.reflex;

/**
 * The onboarding contract of an autonomous mechanism (constitution §6): a Reflex
 * is NOT a runtime concept — no scheduler, no tick — it is the registration
 * paperwork every instinct files with the {@link ReflexRegistry} so that
 *
 * <ul>
 *   <li>its one-line self-description joins the reflex overview the model reads
 *       (via {@link ReflexRegistry#overview()});</li>
 *   <li>it gets a per-instinct enabled switch the owner can flip (persisted,
 *       consulted at each instinct's own decision entry point).</li>
 * </ul>
 *
 * <p>Execution stays where it always was: an instinct that needs the body
 * implements {@code TaskChain} exactly as before and ALSO implements this; a
 * pure policy (tool durability guard, food filter) registers a descriptor only
 * — there is no second scheduler.
 */
public interface Reflex {

    /** Stable id — the enabled-switch key and config-file key (snake_case). */
    String id();

    /** One-line Chinese self-description, e.g. "会自动穿上更好的盔甲"; joined
     *  into the "你的身体有这些本能:…" overview the model sees. */
    String describe();
}
