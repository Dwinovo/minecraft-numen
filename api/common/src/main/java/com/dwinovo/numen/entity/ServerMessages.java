package com.dwinovo.numen.entity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端对她说的话(系统聊天与动作栏)怎么交给模型。聊天栏与动作栏是两种东西,各按各的样子交。
 *
 * <h2>聊天栏:一句一句,同一句折叠</h2>
 * 聊天栏里的话一句接一句留着,每一句都是一件事。模组常把同一句提示每刻、每秒重发一遍(离家太远、能量不够),每一遍都交,
 * 一句话就能把队列刷满、把真正的事挤掉。所以:
 * <ul>
 *   <li>同一句头一回说当场交;</li>
 *   <li>从交出那一刻起的 {@link #WINDOW_TICKS} 刻里再说,只记遍数;</li>
 *   <li>窗口到了,这期间又说过的,交一条带遍数的,并从这一刻重开窗口;没再说过的就此忘掉,下次再说又是头一回。</li>
 * </ul>
 * 于是一句不停刷的话每个窗口交一条、带着遍数;说了几遍就停的,事后补一条遍数;只说一次的,就是一条。不同的话各折各的。
 *
 * <h2>动作栏:一格,最新的为准</h2>
 * 动作栏只有一行,新的一句盖掉旧的——模组拿它当状态栏,每刻发一句数值不同的话,按原文折叠拦不住。所以动作栏当一格看:
 * <ul>
 *   <li>还没交出去的只留一句,新来的盖掉它;</li>
 *   <li>这一格在 {@link #WINDOW_TICKS} 刻里最多交一次:离上次交出够了一个窗口就当场交,不够就等窗口到了交那时最新的一句;</li>
 *   <li>和上次交出去的是同一句、这一格又一直没空过(一个窗口之内又说过),就是那句话还挂在那儿,不算新话,不交。
 *       空了一个窗口以后再说,是又说了一遍,照交。</li>
 * </ul>
 * 安静之后的头一句当场交,所以她按了床、原版回一句"只能夜里睡",这一句不等窗口;每刻刷新的状态栏最多每个窗口一条。
 *
 * <p>窗口 30 秒:状态栏刷一小时最多一百二十条,远低于队列的容量;动作栏上两句不同的话挨得比这更近时,后一句最多晚半分钟到,
 * 而这些话本来就随下一次调模型捎带,不叫醒她。
 *
 * <p>空文本不算话:模组清空动作栏发的就是一句空文本。纯逻辑,不碰 Minecraft:时刻由调用方给(服务器刻数),交出去的去处由
 * {@link Sink} 接。
 */
final class ServerMessages {

    /** 折叠窗口:30 秒。聊天栏同一句、动作栏这一格都按它。 */
    static final int WINDOW_TICKS = 30 * 20;

    /** 交出去的那一条。 */
    interface Sink {
        /**
         * @param overlay 显示在动作栏
         * @param repeats 上次交出之后又说了几遍;0 = 头一回(动作栏恒为 0)
         */
        void tell(String text, boolean overlay, int repeats);
    }

    private final Sink sink;
    /** 聊天栏里窗口还开着的每一句,按原文认。 */
    private final Map<String, Heard> chat = new LinkedHashMap<>();

    /** 动作栏上还没交出去的最新一句;没有为 null。 */
    private String barPending;
    /** 动作栏上次交出去的那一句;还没交过为 null。 */
    private String barTold;
    /** 动作栏上次交出的那一刻。 */
    private long barToldAt = -WINDOW_TICKS;
    /** 动作栏上次有话的那一刻。 */
    private long barHeardAt = -WINDOW_TICKS;

    ServerMessages(Sink sink) {
        this.sink = sink;
    }

    /** 服务端刚对她说了一句。 */
    void heard(String text, boolean overlay, long now) {
        if (text.isBlank()) {
            return;
        }
        if (overlay) {
            heardOnBar(text, now);
        } else {
            heardInChat(text, now);
        }
    }

    private void heardInChat(String text, long now) {
        Heard same = chat.get(text);
        if (same != null) {
            same.repeats++;
            return;
        }
        chat.put(text, new Heard(text, now));
        sink.tell(text, false, 0);
    }

    private void heardOnBar(String text, long now) {
        boolean stillUp = text.equals(barTold) && now - barHeardAt < WINDOW_TICKS;
        barHeardAt = now;
        barPending = stillUp ? null : text;
        tellBar(now);
    }

    /** 动作栏有待交的一句、离上次交出也够了一个窗口,就交。 */
    private void tellBar(long now) {
        if (barPending == null || now - barToldAt < WINDOW_TICKS) {
            return;
        }
        sink.tell(barPending, true, 0);
        barTold = barPending;
        barToldAt = now;
        barPending = null;
    }

    /** 每刻一次:动作栏到了窗口的交最新一句;聊天栏到了窗口的,又说过的交一条遍数并重开窗口,没再说过的忘掉。 */
    void tick(long now) {
        tellBar(now);
        for (Iterator<Heard> it = chat.values().iterator(); it.hasNext(); ) {
            Heard h = it.next();
            if (now - h.since < WINDOW_TICKS) {
                continue;
            }
            if (h.repeats == 0) {
                it.remove();
                continue;
            }
            sink.tell(h.text, false, h.repeats);
            h.repeats = 0;
            h.since = now;
        }
    }

    private static final class Heard {
        final String text;
        /** 这一窗口从哪一刻起(上一次交出的那一刻)。 */
        long since;
        /** 这一窗口里又说了几遍。 */
        int repeats;

        Heard(String text, long since) {
            this.text = text;
            this.since = since;
        }
    }
}
