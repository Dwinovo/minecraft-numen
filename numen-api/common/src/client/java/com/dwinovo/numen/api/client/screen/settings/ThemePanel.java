package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.client.screen.UiTheme;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextWrap;
import com.dwinovo.numen.api.client.ui.widget.Label;
import com.dwinovo.numen.api.client.ui.widget.ListView;
import com.dwinovo.numen.api.client.ui.widget.Slider;
import com.dwinovo.numen.api.client.ui.widget.Toggle;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.client.ui.widget.UiRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 主题分区——NumenUI 版的瓤:主题行(三色小样 + 名字 + 当前 ✔,点击即切换
 * 并写入 ui.json)+ 快捷对话提醒、消息通知两个开关行。切主题即触发宿主调色板重读。
 */
public final class ThemePanel {

    private static final int ROW_H = 24;

    private final UiRoot ui = new UiRoot();
    private final Runnable onThemeChanged;
    private ListView<UiTheme> list;

    public ThemePanel(Runnable onThemeChanged) {
        this.onThemeChanged = onThemeChanged;
    }

    public void build(int x, int y, int w, int h) {
        ui.clear();

        list = ui.add(new ListView<UiTheme>(UiTheme.ALL, ROW_H, this::renderRow, null)
                .rowClick((index, xInRow) -> {
                    UiTheme.select(UiTheme.ALL.get(index).id());
                    onThemeChanged.run();   // 屏幕的调色板常量重读新主题
                    return true;
                }));
        int body = y;   // 这一页叫什么在面板抬头上,没有标题行
        int listH = Math.min(UiTheme.ALL.size() * ROW_H, y + h - body - 30);
        list.setBounds(x, body, w, listH);

        // 快捷对话提醒开关行(默认开:准星指着同伴时浮「按 [键] 对话」)。
        int hy = body + listH + 8;
        Toggle hint = ui.add(new Toggle(com.dwinovo.numen.api.client.data.ClientPrefs.talkHint(),
                com.dwinovo.numen.api.client.data.ClientPrefs::setTalkHint));
        hint.setBounds(x, hy, 22, 11);
        int hintH = wrapped(I18n.get(ModLanguageData.Keys.THEME_TALK_HINT), x + 28, hy + 1, w - 28, Label.Role.SECONDARY);

        // 消息通知开关行(默认开:她说了话、主人没在面板里看着那个会话时,右下角弹一张卡)。
        int ny = hy + Math.max(NumenStyle.ROW_PITCH, hintH + 6);
        Toggle notices = ui.add(new Toggle(com.dwinovo.numen.api.client.data.ClientPrefs.messageNotices(),
                com.dwinovo.numen.api.client.data.ClientPrefs::setMessageNotices));
        notices.setBounds(x, ny, 22, 11);
        int noticesH = wrapped(t(com.dwinovo.numen.api.data.ModLanguageData.Keys.SETTINGS_MESSAGE_NOTICES),
                x + 28, ny + 1, w - 28, Label.Role.SECONDARY);

        // 主动性:她多久把攒下的世界变化说一次。这是"及时性"的旋钮,不是"话多话少"——
        // 拉小知道得及时、token 烧得快,拉大知道得晚、省。默认 3。
        int sy = ny + Math.max(NumenStyle.ROW_PITCH, noticesH + 6);
        String initiativeText = I18n.get(ModLanguageData.Keys.THEME_INITIATIVE);
        Label initTitle = ui.add(new Label(initiativeText, Label.Role.SECONDARY));
        initTitle.setBounds(x, sy + 1, Minecraft.getInstance().font.width(initiativeText), 9);
        Slider initiative = ui.add(new Slider(
                EventQueue.MIN_LEVEL, EventQueue.MAX_LEVEL, 1,
                com.dwinovo.numen.api.client.agent.ServerLibraries.initiative(),
                v -> com.dwinovo.numen.api.client.agent.ServerLibraries.setInitiative((int) v),
                v -> String.valueOf((int) v)));
        initiative.setBounds(x + 44, sy, Math.min(140, w - 44), NumenStyle.CONTROL_H);
        wrapped(I18n.get(ModLanguageData.Keys.THEME_INITIATIVE_HINT), x, sy + NumenStyle.CONTROL_H + 3, w, Label.Role.SECONDARY);
    }

    /**
     * 一段说明折进 {@code width} 宽,每行一个 {@link Label} 自上而下排,返回这段占的高。
     * 英文比中文长得多,放不下的字不能交给页面的裁剪框去切。
     */
    private int wrapped(String text, int lx, int ly, int width, Label.Role role) {
        var font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 1;
        java.util.List<String> lines = TextWrap.wrap(text, width, font::width, 0);
        for (int i = 0; i < lines.size(); i++) {
            ui.add(new Label(lines.get(i), role)).setBounds(lx, ly + i * lineH, width, 9);
        }
        return Math.max(1, lines.size()) * lineH;
    }

    private void renderRow(IDrawSurface s, NumenTheme.Colors c, UiTheme t, int index,
                           int rx, int ry, int rw, int rh, boolean selected, boolean hovered) {
        boolean cur = t == UiTheme.current();   // 行悬停底由 ListView 统一画
        // 描边环:整块底当"框",三色小样叠在内缩区上。
        s.fillRect(rx, ry + 3, 34, 18, cur ? c.accent() : c.divider());
        s.fillRect(rx + 2, ry + 5, 10, 14, t.ground());
        s.fillRect(rx + 12, ry + 5, 10, 14, t.band());
        s.fillRect(rx + 22, ry + 5, 10, 14, t.cta());
        String name = I18n.get(t.isDark() ? "numen.menu.night" : "numen.menu.day");   // 主题名与 ☰ 菜单里同一份语言键
        s.drawText(name, rx + 40, ry + 8, cur ? c.textPrimary() : c.textSecondary(), false);
        if (cur) {
            s.drawText("✔", rx + 40 + s.textWidth(name) + 6, ry + 8, c.success(), false);
        }
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return ui.mouseScrolled(mx, my, delta);
    }

    private static String t(String key) {
        return I18n.get(key);
    }
}
