package com.dwinovo.numen.task.reflex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reflex roster (constitution §6): every autonomous mechanism registers here
 * once at init, and in return gets, for free,
 *
 * <ul>
 *   <li><b>self-description into the prompt</b> — {@link #overview()} joins the
 *       enabled reflexes' one-liners into the "你的身体有这些本能:…你的显式动作
 *       永远优先" paragraph. numen-api exposes no system-prompt extension point
 *       (scouted: {@code EntityAgentLoop.composeSystemPrompt} appends only
 *       persona/env/known_blocks/skills), so the overview rides the
 *       {@code get_self_status} tool DESCRIPTION instead — descriptions are
 *       re-read on every request build ({@code OpenAIProvider}), so the model
 *       sees the current roster each turn;</li>
 *   <li><b>a per-reflex enabled switch</b> — default ON, in-memory, persisted as
 *       a flat JSON map through the bound {@link StateStore}
 *       ({@code config/numen/reflexes.json} in production). A disabled chain
 *       reflex short-circuits its {@code getPriority} to DORMANT; a disabled
 *       policy reflex is checked at its decision-function entry.</li>
 * </ul>
 *
 * <p>Static like {@code SurvivalConfig} (the switches are per-JVM/global, not
 * per-companion), synchronized because the server tick thread flips/reads while
 * the client request-builder thread reads {@link #overview()}. The core is pure
 * JDK so the roster/overview/switch semantics are headless-testable.
 */
public final class ReflexRegistry {

    /** Persistence seam: a flat {@code id -> enabled} map. Production binds the
     *  Gson file store ({@link ReflexStateFile}); tests bind an in-memory map. */
    public interface StateStore {
        Map<String, Boolean> load();

        void save(Map<String, Boolean> state);
    }

    /** Registration order preserved — the overview reads in the order instincts enlisted. */
    private static final Map<String, Reflex> REFLEXES = new LinkedHashMap<>();
    /** Switch overrides; an absent id means enabled (the default is always ON). */
    private static final Map<String, Boolean> ENABLED = new HashMap<>();
    private static StateStore store;

    private ReflexRegistry() {}

    /** Bind the persistence store and apply its saved switch states. Call before
     *  {@link #register} at init; without a store the switches are memory-only. */
    public static synchronized void bindStore(StateStore s) {
        store = s;
        if (s != null) {
            ENABLED.putAll(s.load());
        }
    }

    /** Enlist one reflex. Idempotent by id — a duplicate registration is ignored. */
    public static synchronized void register(Reflex reflex) {
        REFLEXES.putIfAbsent(reflex.id(), reflex);
    }

    /** Is this instinct live? Unregistered/unknown ids default to true so a
     *  check-before-registration ordering bug fails open (behavior unchanged). */
    public static synchronized boolean enabled(String id) {
        return ENABLED.getOrDefault(id, Boolean.TRUE);
    }

    /** Flip one instinct's switch and persist the full switch table. */
    public static synchronized void setEnabled(String id, boolean on) {
        ENABLED.put(id, on);
        if (store != null) {
            Map<String, Boolean> state = new LinkedHashMap<>();
            for (String known : REFLEXES.keySet()) {
                state.put(known, ENABLED.getOrDefault(known, Boolean.TRUE));
            }
            // Keep overrides for ids not (yet) registered this session too.
            ENABLED.forEach(state::putIfAbsent);
            store.save(state);
        }
    }

    /**
     * The reflex overview for the model: enabled reflexes' self-descriptions
     * joined into one paragraph, ending on the constitutional guarantee that
     * explicit actions always win. Empty when nothing is registered/enabled.
     */
    public static synchronized String overview() {
        List<String> lines = new ArrayList<>();
        for (Reflex r : REFLEXES.values()) {
            if (enabled(r.id())) {
                lines.add(r.describe());
            }
        }
        if (lines.isEmpty()) return "";
        return "你的身体有这些本能,会自动发生,不需要用工具去做:"
                + String.join(";", lines)
                + "。你的显式动作永远优先——用 equip_item 显式穿戴会钉住那个槽位,本能不再更换它;"
                + "equip_item 的 item_id 传 \"auto\" 可解除钉,交还本能管理。";
    }

    /** Test hook: wipe the roster, switches and store binding. */
    static synchronized void resetForTest() {
        REFLEXES.clear();
        ENABLED.clear();
        store = null;
    }
}
