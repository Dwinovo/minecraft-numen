package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.ListView;
import com.dwinovo.numen.client.ui.widget.Slider;
import com.dwinovo.numen.client.ui.widget.Toggle;
import com.dwinovo.numen.event.EventQueue;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * 主题分区——NumenUI 版的瓤:主题行(三色小样 + 名字 + 当前 ✔,点击即切换
 * 并写入 ui.json)+ 快捷对话提醒开关行。切主题即触发宿主调色板重读。
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
        Label title = ui.add(new Label(t("numen.settings.theme.title"), Label.Role.PRIMARY));
        title.setBounds(x, y, w, 9);

        list = ui.add(new ListView<UiTheme>(UiTheme.ALL, ROW_H, this::renderRow, null)
                .rowClick((index, xInRow) -> {
                    UiTheme.select(UiTheme.ALL.get(index).id());
                    onThemeChanged.run();   // 屏幕的调色板常量重读新主题
                    return true;
                }));
        int listH = UiTheme.ALL.size() * ROW_H;
        list.setBounds(x, y + 16, w, Math.min(listH, h - 16 - 30));

        // 快捷对话提醒开关行(默认开:准星指着同伴时浮「按 [键] 对话」)。
        int hy = y + 16 + Math.min(listH, h - 16 - 30) + 8;
        Toggle hint = ui.add(new Toggle(com.dwinovo.numen.client.data.ClientPrefs.talkHint(),
                com.dwinovo.numen.client.data.ClientPrefs::setTalkHint));
        hint.setBounds(x, hy, 22, 11);
        String label = "快捷对话提醒(准星指着同伴时提示按键)";
        Label hintLabel = ui.add(new Label(label, Label.Role.SECONDARY));
        hintLabel.setBounds(x + 28, hy + 1, Minecraft.getInstance().font.width(label), 9);

        // 主动性:她多久把攒下的世界变化说一次。这是"及时性"的旋钮,不是"话多话少"——
        // 拉小知道得及时、token 烧得快,拉大知道得晚、省。默认 3。
        int sy = hy + NumenStyle.ROW_PITCH;
        Label initTitle = ui.add(new Label("主动性", Label.Role.SECONDARY));
        initTitle.setBounds(x, sy + 1, Minecraft.getInstance().font.width("主动性"), 9);
        Slider initiative = ui.add(new Slider(
                EventQueue.MIN_LEVEL, EventQueue.MAX_LEVEL, 1,
                com.dwinovo.numen.client.data.ClientPrefs.initiativeLevel(),
                v -> com.dwinovo.numen.client.data.ClientPrefs.setInitiativeLevel((int) v),
                v -> String.valueOf((int) v)));
        initiative.setBounds(x + 44, sy, Math.min(140, w - 44), NumenStyle.CONTROL_H);
        String hintText = "小 = 世界变化知道得及时,更费 token;大 = 知道得晚,更省";
        Label initHint = ui.add(new Label(hintText, Label.Role.MUTED));
        initHint.setBounds(x, sy + NumenStyle.CONTROL_H + 3,
                Minecraft.getInstance().font.width(hintText), 9);
    }

    private void renderRow(IDrawSurface s, NumenTheme.Colors c, UiTheme t, int index,
                           int rx, int ry, int rw, int rh, boolean selected, boolean hovered) {
        boolean cur = t == UiTheme.current();   // 行悬停底由 ListView 统一画
        // 圆角描边环:整块圆角底当"框",三色小样叠在内缩区上。
        s.fillRoundRect(rx, ry + 3, 34, 18, 4, cur ? c.accent() : c.divider());
        s.fillRect(rx + 2, ry + 5, 10, 14, t.ground());
        s.fillRect(rx + 12, ry + 5, 10, 14, t.band());
        s.fillRect(rx + 22, ry + 5, 10, 14, t.cta());
        s.drawText(t.label(), rx + 40, ry + 8, cur ? c.textPrimary() : c.textMuted(), false);
        if (cur) {
            s.drawText("✔", rx + 40 + s.textWidth(t.label()) + 6, ry + 8, c.success(), false);
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
