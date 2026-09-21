package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.skin.CompanionFace;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 邀请卡:把名册上还不在这个会话里的同伴请进来。和召唤卡、编辑卡同一族(暗幕 + 居中卡):
 * 抬头一行标题、一行说明;正文一列行,每行脸 + 名字 + 勾选格,行间一道横线;收尾 [取消][邀请]。
 * 主流(Discord、微信)都是"勾选 + 一个确认按钮"。一个都没勾时邀请钮置灰——没东西可确认。
 *
 * <p>脸是 MC 独有的东西,在这一层直接画(见 ui-design-rules "分层");几何与状态色照样走 NumenStyle。
 */
public final class InvitePanel implements ModalCard {

    /** 屏幕侧的面:哪个会话、请了谁、关卡。 */
    public interface Host {
        Conversation conversation();

        void onInvite(List<UUID> picked);

        void onClose();
    }

    private static final int FACE = 18;
    /** 一行:脸 + 上下各 3,和左栏格的呼吸一致。 */
    private static final int ROW_H = FACE + 6;
    private static final int BOX = 9;
    private static final int MAX_ROWS = 6;
    private static final int TITLE_H = 20;
    private static final int HINT_H = 14;
    private static final int FOOTER_H = 16;

    private final UiRoot ui = new UiRoot();
    private final Host host;
    private List<UUID> candidates = List.of();
    private final Set<UUID> picked = new LinkedHashSet<>();
    private Button invite;
    /** 行区几何(渲染与命中共用)。 */
    private int rowsX, rowsY, rowsW;
    private int windowStart;

    public InvitePanel(Host host) {
        this.host = host;
    }

    @Override
    public void reset() {
        candidates = Conversations.instance().pullable(host.conversation());
        picked.clear();
        windowStart = 0;
    }

    private int shownRows() {
        return Math.min(candidates.size(), MAX_ROWS);
    }

    @Override
    public int height() {
        return 6 + TITLE_H + HINT_H + 4 + shownRows() * ROW_H + 8 + FOOTER_H + 8;
    }

    @Override
    public void build(int x, int y, int w, int h, int dropBottom) {
        ui.clear();
        ui.setViewportHeight(dropBottom);
        Conversation conv = host.conversation();
        // 就他俩时屏幕在标题左侧补画她的脸,文字给它让出 24px——和编辑卡同一格式
        int lead = Conversations.instance().soloOf(conv) != null ? 24 : 0;

        int ry = y;
        Label title = ui.add(new Label(t(ModLanguageData.Keys.CONVO_INVITE_TITLE), Label.Role.PRIMARY));
        title.setBounds(x + lead, ry + 5, w - lead, 9);
        ry += TITLE_H;
        Label hint = ui.add(new Label(
                I18n.get(ModLanguageData.Keys.CONVO_INVITE_HINT, conv.displayName(NumenRoster.instance()::name)),
                Label.Role.MUTED));
        hint.setBounds(x, ry, w, 9);
        ry += HINT_H + 4;

        rowsX = x;
        rowsY = ry;
        rowsW = w;
        ry += shownRows() * ROW_H + 8;

        int bw = 64, gap = 8;
        int bx = x + w - (bw * 2 + gap);
        Button cancel = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL),
                Button.Style.NORMAL, host::onClose));
        cancel.setBounds(bx, ry, bw, FOOTER_H);
        invite = ui.add(new Button(t(ModLanguageData.Keys.CONVO_INVITE_CONFIRM),
                Button.Style.ACCENT, this::confirm));
        invite.setBounds(bx + bw + gap, ry, bw, FOOTER_H);
        invite.setEnabled(!picked.isEmpty());
    }

    private void confirm() {
        if (picked.isEmpty()) return;
        host.onInvite(new ArrayList<>(picked));
    }

    // ---- 宿主转发面 ----

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int shown = shownRows();
        windowStart = Math.max(0, Math.min(windowStart, candidates.size() - shown));
        int hot = rowAt(mouseX, mouseY);
        for (int i = 0; i < shown; i++) {
            UUID who = candidates.get(windowStart + i);
            int ry = rowsY + i * ROW_H;
            boolean on = picked.contains(who);
            if (hot == windowStart + i) {
                s.fillRect(rowsX, ry, rowsW, ROW_H, c.hover());
            }
            if (i > 0) {
                s.fillRect(rowsX, ry, rowsW, 1, c.divider());   // 行与行之间一道横线,不套框
            }
            int fy = ry + (ROW_H - FACE) / 2;
            NumenStyle.box(s, rowsX + 1, fy - 1, FACE + 2, FACE + 2, c.inputBg(), on ? c.accent() : c.inputBorder());
            if (s instanceof McDrawSurface m) {
                CompanionFace.draw(m.graphics(), who, KnownSkins.of(who), rowsX + 2, fy, FACE);
            }
            String name = NumenRoster.instance().name(who);
            s.drawText(name == null ? "?" : name, rowsX + FACE + 10, ry + (ROW_H - s.lineHeight()) / 2 + 1,
                    on ? c.textPrimary() : c.textSecondary(), false);
            // 勾选格:勾上是强调色实底,没勾是输入框的底与描边——和面板里其它"选没选"同一语言
            int bx = rowsX + rowsW - BOX - 4;
            int by = ry + (ROW_H - BOX) / 2;
            NumenStyle.box(s, bx, by, BOX, BOX, on ? c.accent() : c.inputBg(), on ? c.accent() : c.inputBorder());
            if (on) {
                s.fillRect(bx + 3, by + 3, BOX - 6, BOX - 6, 0xFFFFFFFF);
            }
        }
        if (candidates.size() > shown) {
            // 装不下的用"+N"说,不挤行
            String more = "+" + (candidates.size() - shown);
            s.drawText(more, rowsX + rowsW - s.textWidth(more), rowsY + shown * ROW_H + 1, c.textMuted(), false);
        }
        if (invite != null) invite.setEnabled(!picked.isEmpty());
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 鼠标下的行(candidates 下标),不在行上则 -1。 */
    private int rowAt(double mx, double my) {
        if (mx < rowsX || mx >= rowsX + rowsW) return -1;
        for (int i = 0; i < shownRows(); i++) {
            int ry = rowsY + i * ROW_H;
            if (my >= ry && my < ry + ROW_H) return windowStart + i;
        }
        return -1;
    }

    @Override
    public String tooltipAt(double mx, double my) {
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int row = rowAt(mx, my);
        if (button == 0 && row >= 0) {
            UUID who = candidates.get(row);
            if (!picked.remove(who)) picked.add(who);
            return true;
        }
        return ui.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (candidates.size() <= MAX_ROWS) return ui.mouseScrolled(mx, my, delta);
        windowStart = Math.max(0, Math.min(windowStart - (int) Math.signum(delta), candidates.size() - MAX_ROWS));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (keyCode == KeyCodes.ENTER) {
            confirm();   // Enter 是确认的兜底路径;没勾就不动
            return true;
        }
        return ui.keyPressed(keyCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch) {
        return ui.charTyped(ch);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
