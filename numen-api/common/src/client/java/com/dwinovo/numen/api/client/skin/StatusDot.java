package com.dwinovo.numen.api.client.skin;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.client.agent.ConversationStatus;
import com.dwinovo.numen.api.client.agent.Conversations;
import com.dwinovo.numen.api.client.agent.ConversationStatuses;
import com.dwinovo.numen.api.client.screen.UiTheme;
import com.dwinovo.numen.api.client.ui.Anim;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 头像的状态:不在线的她整张脸蒙灰、不标任何东西;在线的右下角一枚标记——黄点等你、红点出错、蓝点有新话、小转圈在干活,
 * 闲着什么都不画。左栏、转盘、消息通知都在画完脸之后从这里画,灰脸、位置、大小、窗口底色的描边(压在脸上也看得清)、
 * 换状态时的淡入淡出只此一份。
 */
public final class StatusDot {

    /** 换状态时旧的淡出、新的淡入用的时长。 */
    private static final long FADE_MS = 160;
    /** 转圈转一整圈的时长。 */
    private static final long SPIN_MS = 800;
    private static final int SPIN_POINTS = 10;

    /** 一个会话上一次换状态:从 {@code from} 换到 {@code to} 是 {@code since} 那一刻。 */
    private record Change(ConversationStatus from, ConversationStatus to, long since) {}

    private static final Map<String, Change> CHANGES = new HashMap<>();

    private StatusDot() {}

    /**
     * 画会话 {@code c} 的状态,盖在 {@code (x, y)} 起、边长 {@code size} 的头像上。
     *
     * @param viewing 主人此刻正对着这个会话(它没有"没看过")
     * @param alpha   整体透明度,头像自己在淡入淡出时跟着走
     */
    public static void draw(GuiGraphics g, Conversation c, boolean viewing, int x, int y, int size, float alpha) {
        UUID her = Conversations.instance().soloOf(c);
        if (her != null && !ConversationStatuses.online(her)) {
            UiTheme t = UiTheme.current();
            g.fill(x, y, x + size, y + size, fade(UiTheme.mix(t.band(), 0xFF808080, 0.5f) & 0xFFFFFF | 0xB0000000, alpha));
        }
        ConversationStatus now = ConversationStatuses.of(c, viewing);
        long ms = Util.getMillis();
        Change last = CHANGES.get(c.id());
        if (last == null) {
            last = new Change(now, now, ms - FADE_MS);
        } else if (last.to() != now) {
            last = new Change(last.to(), now, ms);
        }
        CHANGES.put(c.id(), last);
        float k = Anim.easeOutCubic((ms - last.since()) / (float) FADE_MS);
        // 点比头像小一圈,盖住右下角;描边是窗口底色,所以在亮暗的脸上都分得出来
        int d = Math.clamp(Math.round(size * 0.3f), 7, 13);
        int dx = x + size - d + 1, dy = y + size - d + 1;
        if (k < 1f) paint(g, last.from(), dx, dy, d, alpha * (1f - k), ms);
        paint(g, last.to(), dx, dy, d, alpha * k, ms);
    }

    private static void paint(GuiGraphics g, ConversationStatus s, int x, int y, int d, float alpha, long ms) {
        if (s == ConversationStatus.NONE || alpha <= 0.01f) return;
        UiTheme t = UiTheme.current();
        g.fill(x - 1, y - 1, x + d + 1, y + d + 1, fade(t.band(), alpha));
        switch (s) {
            case WAITING -> g.fill(x, y, x + d, y + d, fade(t.run(), alpha));
            case ERROR -> g.fill(x, y, x + d, y + d, fade(t.fail(), alpha));
            case NEW -> g.fill(x, y, x + d, y + d, fade(t.cta(), alpha));
            case WORKING -> spinner(g, x, y, d, fade(t.run(), alpha), ms);
            case NONE -> { }
        }
    }

    /** 一圈小方点,亮的一粒绕着转、后面拖一截渐暗的尾巴。 */
    private static void spinner(GuiGraphics g, int x, int y, int d, int color, long ms) {
        float head = (ms % SPIN_MS) / (float) SPIN_MS * SPIN_POINTS;
        float r = (d - 2) / 2f;
        float cx = x + d / 2f, cy = y + d / 2f;
        int dot = d >= 10 ? 2 : 1;
        for (int i = 0; i < SPIN_POINTS; i++) {
            float behind = ((head - i) % SPIN_POINTS + SPIN_POINTS) % SPIN_POINTS / SPIN_POINTS;
            double ang = i * Math.PI * 2 / SPIN_POINTS - Math.PI / 2;
            int px = Math.round(cx + (float) Math.cos(ang) * r - dot / 2f);
            int py = Math.round(cy + (float) Math.sin(ang) * r - dot / 2f);
            g.fill(px, py, px + dot, py + dot, fade(color, 1f - behind * 0.8f));
        }
    }

    private static int fade(int argb, float f) {
        int a = Math.round((argb >>> 24) * Math.clamp(f, 0f, 1f));
        return (argb & 0xFFFFFF) | (a << 24);
    }
}
