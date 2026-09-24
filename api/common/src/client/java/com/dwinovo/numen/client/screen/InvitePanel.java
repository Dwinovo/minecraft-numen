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
import com.dwinovo.numen.client.ui.TextClip;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 邀请卡:名册上的同伴一排脸,名字在脸下。已经在会话里的高亮着、点不动;其余的点一下选中、
 * 再点取消;收尾 [取消][邀请]。卡按脸的数量收窄,字只有一个标题——要看的是脸,不是说明。
 * 和召唤卡、编辑卡同一族(暗幕 + 居中方角卡)。
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

    private static final int FACE = 26;
    private static final int TILE_W = 40;
    private static final int TILE_GAP = 4;
    private static final int TITLE_H = 18;
    private static final int NAME_H = 9;
    private static final int FOOTER_H = 16;
    private static final int BTN_W = 52;

    private final UiRoot ui = new UiRoot();
    private final Host host;
    private List<UUID> everyone = List.of();
    private final Set<UUID> members = new HashSet<>();
    private final Set<UUID> picked = new LinkedHashSet<>();
    private Button invite;
    /** 那一排脸的几何(渲染与命中共用)。 */
    private int tilesX, tilesY;

    public InvitePanel(Host host) {
        this.host = host;
    }

    @Override
    public void reset() {
        Conversation conv = host.conversation();
        List<UUID> all = new ArrayList<>();
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) all.add(e.uuid());
        everyone = all;
        members.clear();
        members.addAll(Conversations.instance().membersAlive(conv));
        picked.clear();
    }

    private int rowW() {
        int n = Math.max(1, everyone.size());
        return n * TILE_W + (n - 1) * TILE_GAP;
    }

    @Override
    public int width() {
        // 内容宽 = 一排脸,至少放得下两颗钮;两侧各 10 是屏幕给卡内容的留白
        return Math.max(rowW(), BTN_W * 2 + 6) + 20;
    }

    @Override
    public int height() {
        return 6 + TITLE_H + 4 + FACE + 4 + NAME_H + 10 + FOOTER_H + 8;
    }

    @Override
    public void build(int x, int y, int w, int h, int dropBottom) {
        ui.clear();
        ui.setViewportHeight(dropBottom);

        Label title = ui.add(new Label(t(ModLanguageData.Keys.CONVO_INVITE_TITLE), Label.Role.PRIMARY));
        title.setBounds(x, y + 5, w, 9);
        tilesX = x + (w - rowW()) / 2;
        tilesY = y + TITLE_H + 4;

        int fy = tilesY + FACE + 4 + NAME_H + 10;
        int gap = 6;
        int bx = x + w - (BTN_W * 2 + gap);
        Button cancel = ui.add(new Button(t(ModLanguageData.Keys.GUI_SETTINGS_CANCEL),
                Button.Style.LINK, host::onClose));
        cancel.setBounds(bx, fy, BTN_W, FOOTER_H);
        invite = ui.add(new Button(t(ModLanguageData.Keys.CONVO_INVITE_CONFIRM),
                Button.Style.LINK, this::confirm));
        invite.setBounds(bx + BTN_W + gap, fy, BTN_W, FOOTER_H);
        invite.setEnabled(!picked.isEmpty());
    }

    private void confirm() {
        if (picked.isEmpty()) return;
        host.onInvite(new ArrayList<>(picked));
    }

    private int tileX(int i) {
        return tilesX + i * (TILE_W + TILE_GAP);
    }

    // ---- 宿主转发面 ----

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int hot = tileAt(mouseX, mouseY);
        for (int i = 0; i < everyone.size(); i++) {
            UUID who = everyone.get(i);
            boolean in = members.contains(who);
            boolean on = picked.contains(who);
            int fx = tileX(i) + (TILE_W - FACE) / 2;
            // 已在会话里:选中底 + 强调描边,名字也是强调色——"她已经在了"。
            // 勾上的:强调描边;没勾的:平时的描边,悬停亮一点。
            int fill = in ? c.selected() : c.inputBg();
            int border = in || on ? c.accent() : hot == i ? NumenStyle.hoverBrighten(c.inputBorder()) : c.inputBorder();
            NumenStyle.box(s, fx - 2, tilesY - 2, FACE + 4, FACE + 4, fill, border);
            if (s instanceof McDrawSurface m) {
                CompanionFace.draw(m.graphics(), who, KnownSkins.of(who), fx, tilesY, FACE);
            }
            if (on) {
                // 勾上的右上角一小块强调色,和左栏"等你点头"那枚记号同一位置、同一语法
                s.fillRect(fx + FACE - 5, tilesY - 3, 7, 7, c.accent());
            }
            String name = NumenRoster.instance().name(who);
            String shown = TextClip.fit(s, name == null ? "?" : name, TILE_W);
            s.drawText(shown, tileX(i) + (TILE_W - s.textWidth(shown)) / 2, tilesY + FACE + 4,
                    in ? c.accent() : on ? c.textPrimary() : c.textMuted(), false);
        }
        if (invite != null) invite.setEnabled(!picked.isEmpty());
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    /** 鼠标下的那一格(everyone 下标),不在格上则 -1。 */
    private int tileAt(double mx, double my) {
        if (my < tilesY - 2 || my >= tilesY + FACE + 4 + NAME_H) return -1;
        for (int i = 0; i < everyone.size(); i++) {
            int tx = tileX(i);
            if (mx >= tx && mx < tx + TILE_W) return i;
        }
        return -1;
    }

    @Override
    public String tooltipAt(double mx, double my) {
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int i = tileAt(mx, my);
        if (button == 0 && i >= 0) {
            UUID who = everyone.get(i);
            if (!members.contains(who) && !picked.remove(who)) picked.add(who);
            return true;
        }
        return ui.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
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
