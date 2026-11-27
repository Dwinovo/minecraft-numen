package com.dwinovo.numen.api.task.reflex;

import com.dwinovo.numen.api.agent.prompt.NumenPrompts;

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
 *       registered reflexes' one-liners into the "# Instincts" section, which the
 *       client puts in the system prompt — the one place the model reads it;</li>
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

    /** Every registered reflex's one-line self-description, in the order they enlisted. */
    public static synchronized List<String> descriptions() {
        List<String> lines = new ArrayList<>();
        for (Reflex r : REFLEXES.values()) {
            lines.add(r.describe());
        }
        return lines;
    }

    /**
     * The reflex overview for the model: the "# Instincts" section of the system prompt,
     * every registered reflex's self-description one per line. Empty when nothing is registered.
     */
    public static synchronized String overview() {
        return overview(descriptions());
    }

    /** 同上,自述由调用方给:主人的客户端上的大脑读的是服务端目录里的那一份。 */
    public static String overview(List<String> lines) {
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(NumenPrompts.INSTINCTS_INTRO);
        lines.forEach(line -> sb.append("\n- ").append(line));
        return sb.toString();
    }

    /** Test hook: wipe the roster. */
    static synchronized void resetForTest() {
        REFLEXES.clear();
    }
}
