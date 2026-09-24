package com.dwinovo.numen.client.notify;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.SelectedCompanion;
import com.dwinovo.numen.client.data.ClientPrefs;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.NumenScreen;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.screen.chat.ConversationPreview;
import com.dwinovo.numen.client.screen.chat.UnreadBadge;
import com.dwinovo.numen.client.skin.ConversationFaces;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.UUID;

/**
 * 消息通知(Telegram Desktop 的桌面通知):她说了话、主人又没在面板里看着那个会话,右下角弹一张小卡——
 * 头像(群是群头像)、会话名、那句话,几秒后自己淡掉。排队、合并、时序在 {@link NoticeStack},这里接线和画。
 *
 * <p>卡片就是左栏的一行:脸、名字、最后一句、未读角标,尺寸与配色和左栏同一套,未读数问
 * {@link ConversationPreview}——和左栏那枚角标是同一个数。
 *
 * <p>点一下打开面板并切到那个会话,右键收掉(Telegram 同样)。游戏里没开界面时鼠标被视角占着,
 * 卡片只看不点;开着任何界面时它画在界面上面、能点。客户端主线程专用。
 */
public final class MessageNotices {

    /** 卡片与左栏一行同高、头像同大(Telegram 的通知就是一行会话)。 */
    private static final int W = 160;
    private static final int H = 34;
    private static final int AV = 26;
    /** 压在界面里的物品、控件上面,让给界面自己的悬停提示(400)。 */
    private static final float Z = 300f;

    /** 卡上画的:哪个会话、谁说的(群里才有)、说了什么。 */
    private record Said(Conversation conv, String who, String text) {}

    /** 一张卡加一格间隔往上摞(Telegram 的 notifyDeltaY),间隔与边距同是 {@link NumenStyle#PAD}。 */
    private static final NoticeStack<Said> STACK = new NoticeStack<>(H + NumenStyle.PAD);

    private MessageNotices() {}

    /**
     * 她说出口了一句(挂在 {@code TurnPresenter} 那唯一的一处)。说在哪个会话里只问循环那一处,
     * 和旁听推送同一个来源:有会话印就是那个落过盘的会话,没有就是"就他俩"。
     */
    public static void spoke(UUID speaker, String said) {
        Conversations convos = Conversations.instance();
        String tag = AgentLoopRegistry.get(speaker).map(EntityAgentLoop::conversation).orElse(null);
        Conversation c = tag == null ? convos.of(speaker) : convos.get(tag);
        if (c == null || !wanted(c)) return;
        String who = convos.soloOf(c) == null ? NumenRoster.instance().name(speaker) : null;
        STACK.push(c.id(), new Said(c, who, said.replace('\n', ' ').replace('\r', ' ').strip()), Util.getMillis());
    }

    /**
     * 这个会话此刻该不该有通知——弹不弹、弹了的留不留,都只问这一处:开关开着,而且主人没在面板里
     * 对着它。面板对着谁就是选中的那个(面板打开、切换都会改选中),所以不必问面板本身。
     */
    private static boolean wanted(Conversation c) {
        if (!ClientPrefs.messageNotices()) return false;
        if (!(Minecraft.getInstance().screen instanceof NumenScreen)) return true;
        Conversation open = Conversations.instance().selected();
        return open == null || !open.id().equals(c.id());
    }

    // ---- 画 ----

    /** HUD 层:没开界面的时候。开着界面时 HUD 在界面底下,改由 {@link #renderOver} 画在上面。 */
    public static void renderHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        com.dwinovo.numen.client.ui.SafeUi.run("message-notices",
                () -> render(g, Integer.MIN_VALUE, Integer.MIN_VALUE, !mc.options.hideGui));
    }

    /** 任何界面画完之后:卡片压在界面上面,指针停上去就不淡。 */
    public static void renderOver(GuiGraphics g, int mouseX, int mouseY) {
        com.dwinovo.numen.client.ui.SafeUi.run("message-notices", () -> render(g, mouseX, mouseY, true));
    }

    private static void render(GuiGraphics g, int mouseX, int mouseY, boolean visible) {
        if (STACK.isIdle()) return;
        long now = Util.getMillis();
        for (var p : STACK.placed(now)) {
            if (p.live() && !wanted(p.payload().conv())) STACK.dismiss(p.key(), now);   // 主人切过去看了,或者关了开关
        }
        STACK.advance(now);
        var hot = hit(mouseX, mouseY, g.guiWidth(), g.guiHeight(), now);
        STACK.hover(hot != null, now);
        if (!visible) return;
        Font font = Minecraft.getInstance().font;
        int x = left(g.guiWidth());
        g.pose().pushPose();
        g.pose().translate(0, 0, Z);
        for (var p : STACK.placed(now)) {
            drawCard(g, font, p.payload(), x, top(g.guiHeight(), p), p.opacity(), hot != null && p.live() && p.key().equals(hot.key()));
        }
        g.pose().popPose();
    }

    /** 一张卡:和左栏一行同一个摆法——脸、名字一行、最后一句一行,未读数的角标贴右端。 */
    private static void drawCard(GuiGraphics g, Font font, Said s, int x, int y, float opacity, boolean hot) {
        UiTheme t = UiTheme.current();
        var surface = new McDrawSurface(g, font);
        g.setColor(1f, 1f, 1f, Math.max(0.05f, opacity));
        // Telegram 的通知底是窗口底色、一圈细描边;浮起来的东西用 aiBorder
        NumenStyle.box(surface, x, y, W, H, hot ? t.over() : t.band(), t.aiBorder());
        int fx = x + (H - AV) / 2, fy = y + (H - AV) / 2;
        NumenStyle.box(surface, fx - 1, fy - 1, AV + 2, AV + 2, t.field(), t.border());
        ConversationFaces.draw(g, s.conv(), fx, fy, AV);
        int tx = fx + AV + 6, right = x + W - 5;
        Nb.text(g, font, Nb.clip(font, s.conv().displayName(NumenRoster.instance()::name), right - tx),
                tx, y + 6, t.onBand());
        // 同一个会话合成一张:只画最新那句,攒了几句看角标;一句的时候不挂
        int unread = ConversationPreview.unread(s.conv(), Conversations.instance().lastSeen(s.conv()));
        int textRight = right;
        if (unread > 1) {
            String n = UnreadBadge.label(unread);
            int bx = right - UnreadBadge.width(font, n);
            UnreadBadge.draw(g, font, n, bx, y + 16, t.cta(), t.onCta());
            textRight = bx - 4;
        }
        int lx = tx, ly = y + 18;
        if (s.who() != null) {
            // 群里先写谁说的,用强调色(Telegram 通知里的作者名)
            String pre = Nb.clip(font, s.who() + ": ", textRight - lx);
            Nb.text(g, font, pre, lx, ly, t.accent());
            lx += font.width(pre);
        }
        Nb.text(g, font, Nb.clip(font, s.text(), textRight - lx), lx, ly, t.textDim());
        g.setColor(1f, 1f, 1f, 1f);
    }

    // ---- 点 ----

    /**
     * 界面收到一次按下:落在某张卡上就归这里——左键打开面板切到那个会话,右键只收掉。
     *
     * @return 这一下被卡片吃掉了,界面不该再处理
     */
    public static boolean click(double mouseX, double mouseY, int button) {
        if (STACK.isIdle()) return false;
        var w = Minecraft.getInstance().getWindow();
        long now = Util.getMillis();
        var p = hit(mouseX, mouseY, w.getGuiScaledWidth(), w.getGuiScaledHeight(), now);
        if (p == null) return false;
        STACK.dismiss(p.key(), now);
        if (button == 0) {
            // 选中它再开面板:面板开在选中的那个上,单聊和群走同一条入口
            SelectedCompanion.set(p.payload().conv());
            NumenScreen.openWorkspace();
        }
        return true;
    }

    /** 指针下那张还占着位的卡;最新的贴底、画在最上面,先查它。 */
    private static NoticeStack.Placed<Said> hit(double mx, double my, int screenW, int screenH, long now) {
        int x = left(screenW);
        if (mx < x || mx >= x + W) return null;
        var cards = STACK.placed(now);
        for (int i = cards.size() - 1; i >= 0; i--) {
            var p = cards.get(i);
            int y = top(screenH, p);
            if (p.live() && my >= y && my < y + H) return p;
        }
        return null;
    }

    private static int left(int screenW) {
        return screenW - NumenStyle.PAD - W;
    }

    private static int top(int screenH, NoticeStack.Placed<Said> p) {
        return screenH - NumenStyle.PAD - H - Math.round(p.lift());
    }
}
