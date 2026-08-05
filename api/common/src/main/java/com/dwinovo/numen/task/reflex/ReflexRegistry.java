package com.dwinovo.numen.task.reflex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reflex roster (constitution §6): every autonomous mechanism registers here
 * once at init, and in return gets, for free,
 *
 * <ul>
 *   <li><b>self-description into the prompt</b> — {@link #overview()} joins the
 *       registered reflexes' one-liners into the "你的身体有这些本能:…你的显式动作
 *       永远优先" paragraph. numen-api exposes no system-prompt extension point
 *       (scouted: {@code EntityAgentLoop.composeSystemPrompt} appends only
 *       persona/env/known_blocks/skills), so the overview rides the
 *       {@code get_self_status} tool DESCRIPTION instead — descriptions are
 *       re-read on every request build ({@code OpenAIProvider}), so the model
 *       sees the current roster each turn;</li>
 *   <li>—— 就这一件。<b>名册即全部</b>:登记了就是开着的,没有"每条本能的开关"。
 *       要做开关,先做主人能按的面板入口,再让这里长出开关——只有开关没有入口的话,
 *       每次启动读一个文件、写一个文件,里面的值永远全是 true。看着有、实际没有,
 *       比明说没做更难查。</li>
 * </ul>
 *
 * <p>静态(名册是每 JVM 一份,不分同伴),synchronized 因为服务端 tick 线程注册、
 * 客户端组装请求的线程读 {@link #overview()}。纯 JDK,所以名册与总览的语义
 * headless 可测。
 */
public final class ReflexRegistry {

    /** Registration order preserved — the overview reads in the order instincts enlisted. */
    private static final Map<String, Reflex> REFLEXES = new LinkedHashMap<>();

    private ReflexRegistry() {}

    /** Enlist one reflex. Idempotent by id — a duplicate registration is ignored. */
    public static synchronized void register(Reflex reflex) {
        REFLEXES.putIfAbsent(reflex.id(), reflex);
    }

    /**
     * The reflex overview for the model: every registered reflex's self-description
     * joined into one paragraph, ending on the constitutional guarantee that
     * explicit actions always win. Empty when nothing is registered.
     */
    public static synchronized String overview() {
        List<String> lines = new ArrayList<>();
        for (Reflex r : REFLEXES.values()) {
            lines.add(r.describe());
        }
        if (lines.isEmpty()) return "";
        return "你的身体有这些本能,会自动发生,不需要用工具去做:"
                + String.join(";", lines)
                + "。你的显式动作永远优先——用 equip_item 显式穿戴会钉住那个槽位,本能不再更换它;"
                + "equip_item 的 item_id 传 \"auto\" 可解除钉,交还本能管理。";
    }

    /** Test hook: wipe the roster. */
    static synchronized void resetForTest() {
        REFLEXES.clear();
    }
}
