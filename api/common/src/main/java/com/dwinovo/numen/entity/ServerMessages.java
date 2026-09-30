package com.dwinovo.numen.entity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端对她说的话(系统聊天与动作栏)怎么交给模型:头一回说的一句当场交,同一句在折叠窗口里再说只记遍数。
 *
 * <h2>为什么要折叠</h2>
 * 模组常把同一句提示每刻、每秒重发一遍(离家太远、能量不够),原版的动作栏提示也会随每次右键重发。每一遍都交,
 * 一句话就能把队列刷满、把真正的事挤掉,还一遍遍叫醒她。折叠的规矩:
 * <ul>
 *   <li>同一处(聊天栏或动作栏)的同一句,头一回说当场交;</li>
 *   <li>从交出那一刻起的 {@link #WINDOW_TICKS} 刻里再说,只记遍数;</li>
 *   <li>窗口到了,这期间又说过的,交一条带遍数的,并从这一刻重开窗口;没再说过的就此忘掉,下次再说又是头一回。</li>
 * </ul>
 * 于是一句不停刷的话每个窗口交一条、带着遍数;说了几遍就停的,事后补一条遍数;只说一次的,就是一条。
 * 不同的话各折各的,互不压着。
 *
 * <p>纯逻辑,不碰 Minecraft:时刻由调用方给(服务器刻数),交出去的去处由 {@link Sink} 接。
 */
final class ServerMessages {

    /** 折叠窗口:30 秒。 */
    static final int WINDOW_TICKS = 30 * 20;

    /** 交出去的那一条。 */
    interface Sink {
        /**
         * @param overlay 显示在动作栏
         * @param repeats 上次交出之后又说了几遍;0 = 头一回
         */
        void tell(String text, boolean overlay, int repeats);
    }

    private final Sink sink;
    /** 窗口还开着的每一句,按"在哪儿 + 原文"认。 */
    private final Map<String, Heard> open = new LinkedHashMap<>();

    ServerMessages(Sink sink) {
        this.sink = sink;
    }

    /** 服务端刚对她说了一句。空的不算话:模组清空动作栏发的就是一句空文本。 */
    void heard(String text, boolean overlay, long now) {
        if (text.isBlank()) {
            return;
        }
        Heard same = open.get(key(text, overlay));
        if (same != null) {
            same.repeats++;
            return;
        }
        open.put(key(text, overlay), new Heard(text, overlay, now));
        sink.tell(text, overlay, 0);
    }

    /** 每刻一次:到了窗口的,又说过的交一条遍数并重开窗口,没再说过的忘掉。 */
    void tick(long now) {
        for (Iterator<Heard> it = open.values().iterator(); it.hasNext(); ) {
            Heard h = it.next();
            if (now - h.since < WINDOW_TICKS) {
                continue;
            }
            if (h.repeats == 0) {
                it.remove();
                continue;
            }
            sink.tell(h.text, h.overlay, h.repeats);
            h.repeats = 0;
            h.since = now;
        }
    }

    private static String key(String text, boolean overlay) {
        return (overlay ? "action_bar:" : "chat:") + text;
    }

    private static final class Heard {
        final String text;
        final boolean overlay;
        /** 这一窗口从哪一刻起(上一次交出的那一刻)。 */
        long since;
        /** 这一窗口里又说了几遍。 */
        int repeats;

        Heard(String text, boolean overlay, long since) {
            this.text = text;
            this.overlay = overlay;
            this.since = since;
        }
    }
}
