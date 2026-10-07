package com.dwinovo.numen.client.notify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 右下角那一摞消息通知的排队、合并与时序,照 Telegram Desktop 的默认通知
 * ({@code window/notifications_manager_default.cpp}、{@code window.style} 的 notify* 那几项)。
 * 纯逻辑:不读钟(时间由调用方给)、不画,画法在 {@link MessageNotices}。
 *
 * <ul>
 *   <li>同时最多摆 {@link #MAX_SHOWN} 张(Telegram 通知条数的默认值),多出来的排队,前面的收走一张才补上。</li>
 *   <li>一个会话只占一张:又来一句就换成最新那句、重新计时,不另起一张;在排队的也一样。</li>
 *   <li>最新的贴底,旧的往上让;让位走 {@link #SHIFT_MS} 的线性位移({@code notifyFastAnim})。</li>
 *   <li>出现:{@link #FADE_MS} 线性淡入。停 {@link #WAIT_MS}({@code notifyWaitLongHide})后开始慢慢淡出,
 *       淡出 {@link #SLOW_HIDE_MS}({@code notifySlowHide}),曲线 easeInCirc——前面几乎不动,最后一下才没。</li>
 *   <li>指针停在任何一张上,全体停止淡出、淡回满;移开的那一刻全体立即开始慢淡出(Telegram 的
 *       {@code stopAllHiding}/{@code startAllHiding},移开不再重新等)。</li>
 *   <li>点开或右键收掉的那张 {@link #FADE_MS} 快速淡出,不再占位——别的立即往下补,它留在原处淡完。</li>
 * </ul>
 *
 * @param <T> 一张通知上画的东西
 */
public final class NoticeStack<T> {

    /** Telegram 通知条数的默认值。 */
    public static final int MAX_SHOWN = 3;
    /** 淡入、快速淡出、让位:{@code notifyFastAnim}。 */
    public static final long FADE_MS = 150;
    public static final long SHIFT_MS = 150;
    /** 出现后停多久开始淡出:{@code notifyWaitLongHide}。 */
    public static final long WAIT_MS = 3000;
    /** 慢淡出:{@code notifySlowHide}。 */
    public static final long SLOW_HIDE_MS = 4000;

    /**
     * 摆出来的一张:{@code live} = 还占着位(收掉的在原处淡完,不再算数也不接点击),
     * {@code lift} 是底边离锚点往上的距离(像素),{@code opacity} 0..1。
     */
    public record Placed<T>(String key, T payload, boolean live, float lift, float opacity) {}

    private final class Notice {
        final String key;
        T payload;
        /** 还占着位:点开、收掉之后不占,只在原处淡完。 */
        boolean linked = true;
        /** 到这一刻开始慢淡出;停着(悬停中、正在淡出)时是 {@link Long#MAX_VALUE}。 */
        long hideAt;
        // 透明度:从 from 到 to,animAt 起算 animMs;hiding 时走 easeInCirc
        float from, to;
        long animAt, animMs;
        boolean slow;
        // 位移:从 shiftFrom 到 shiftTo,shiftAt 起算
        float shiftFrom, shiftTo;
        long shiftAt;

        Notice(String key, T payload, long now, float lift) {
            this.key = key;
            this.payload = payload;
            this.hideAt = now + WAIT_MS;
            fade(0f, 1f, now, FADE_MS, false);
            this.shiftFrom = lift;
            this.shiftTo = lift;
            this.shiftAt = now;
        }

        void fade(float from, float to, long now, long ms, boolean slow) {
            this.from = from;
            this.to = to;
            this.animAt = now;
            this.animMs = ms;
            this.slow = slow;
        }

        float opacity(long now) {
            float t = Math.min(1f, Math.max(0f, (now - animAt) / (float) animMs));
            float e = slow ? 1f - (float) Math.sqrt(1f - t * t) : t;   // easeInCirc / linear
            return from + (to - from) * e;
        }

        boolean hiding() {
            return to == 0f;
        }

        boolean gone(long now) {
            return hiding() && now - animAt >= animMs;
        }

        float lift(long now) {
            float t = Math.min(1f, Math.max(0f, (now - shiftAt) / (float) SHIFT_MS));
            return shiftFrom + (shiftTo - shiftFrom) * t;
        }

        void moveTo(float target, long now) {
            if (target == shiftTo) return;
            shiftFrom = lift(now);
            shiftTo = target;
            shiftAt = now;
        }

        void startHiding(long now) {
            if (!linked || hiding()) return;
            hideAt = Long.MAX_VALUE;
            fade(opacity(now), 0f, now, SLOW_HIDE_MS, true);
        }

        void stopHiding(long now) {
            if (!linked) return;
            hideAt = Long.MAX_VALUE;
            if (hiding()) fade(opacity(now), 1f, now, FADE_MS, false);
        }
    }

    private record Queued<T>(String key, T payload) {}

    /** 一张卡片加上下间隔的高度:第 n 张(从底往上数)离锚点 n 个这么高。 */
    private final float step;
    /** 最早摆出来的在前。 */
    private final List<Notice> shown = new ArrayList<>();
    private final Deque<Queued<T>> queue = new ArrayDeque<>();
    private boolean hovered;

    public NoticeStack(float step) {
        this.step = step;
    }

    /**
     * 这个会话又来了一句。已经摆着的那张换成这句、淡回满、重新计时(悬停中就不计时,等移开);
     * 在排队的换内容;都没有就摆一张新的,摆不下就排队。
     */
    public void push(String key, T payload, long now) {
        for (Notice n : shown) {
            if (n.linked && n.key.equals(key)) {
                n.payload = payload;
                n.stopHiding(now);
                n.hideAt = hovered ? Long.MAX_VALUE : now + WAIT_MS;
                return;
            }
        }
        for (var it = queue.iterator(); it.hasNext(); ) {
            if (it.next().key().equals(key)) {
                it.remove();
                queue.addLast(new Queued<>(key, payload));
                return;
            }
        }
        queue.addLast(new Queued<>(key, payload));
        fill(now);
    }

    /** 点开或右键:这张快速淡出、让出位子,排队的补上。 */
    public void dismiss(String key, long now) {
        queue.removeIf(q -> q.key().equals(key));
        for (Notice n : shown) {
            if (n.linked && n.key.equals(key)) {
                n.linked = false;
                n.hideAt = Long.MAX_VALUE;
                n.fade(n.opacity(now), 0f, now, FADE_MS, false);
            }
        }
        fill(now);
    }

    /**
     * 指针此刻在不在某一张上。放上去:全体停止淡出;移开:全体立即开始慢淡出。
     * 没有指针的时候(游戏里没开界面)一直是 false。
     */
    public void hover(boolean over, long now) {
        if (over == hovered) return;
        hovered = over;
        for (Notice n : shown) {
            if (over) n.stopHiding(now); else n.startHiding(now);
        }
    }

    /** 推进到 {@code now}:到点的开始淡出、淡完的拿掉、空出的位子从队里补。每帧画之前调。 */
    public void advance(long now) {
        for (Notice n : shown) {
            if (n.linked && now >= n.hideAt) n.startHiding(now);
        }
        if (shown.removeIf(n -> n.gone(now))) fill(now);
    }

    /** 现在摆着的,从最早的到最新的(最新的贴底)。 */
    public List<Placed<T>> placed(long now) {
        List<Placed<T>> out = new ArrayList<>(shown.size());
        for (Notice n : shown) {
            out.add(new Placed<>(n.key, n.payload, n.linked, n.lift(now), n.opacity(now)));
        }
        return out;
    }

    public boolean isIdle() {
        return shown.isEmpty() && queue.isEmpty();
    }

    /** 从队里补到摆满,然后按新次序让位:最新的贴底,往上一张一格。 */
    private void fill(long now) {
        int linked = 0;
        for (Notice n : shown) if (n.linked) linked++;
        while (linked < MAX_SHOWN && !queue.isEmpty()) {
            Queued<T> q = queue.pollFirst();
            Notice n = new Notice(q.key(), q.payload(), now, 0f);
            if (hovered) n.hideAt = Long.MAX_VALUE;
            shown.add(n);
            linked++;
        }
        int slot = 0;
        for (int i = shown.size() - 1; i >= 0; i--) {
            Notice n = shown.get(i);
            if (!n.linked) continue;
            n.moveTo(slot * step, now);
            slot++;
        }
    }
}
