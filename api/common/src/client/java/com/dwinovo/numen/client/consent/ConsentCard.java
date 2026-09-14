package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.widget.Badge;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.TextField;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张征询卡:同伴名、原因、清单(服务端组好的正文,撤不回的那几行前面标出来)、倒计时,
 * 一个可选附言框和"允许 / 允许并记住 / 拒绝"三个键。非模态——主人在玩游戏。
 *
 * <p>同一份布局两种用法:聊天面板顶部可以点({@code interactive});HUD 上画不带输入的那一版,
 * 底下一行提示按哪个键打开面板答复——没开界面时鼠标归游戏,HUD 上的键点不到。
 */
public final class ConsentCard {

    /** 清单最多列几行,其余计数。 */
    private static final int MAX_LINES = 6;
    private static final int GAP = 4;

    private final boolean interactive;
    private final UiRoot ui = new UiRoot();

    private ConsentRequestPayload shown;
    private int bx, by, bw, bh;
    private Label countdown;
    private TextField note;
    private final List<Label> lineLabels = new ArrayList<>();
    private final List<Boolean> lineIrreversible = new ArrayList<>();

    public ConsentCard(boolean interactive) {
        this.interactive = interactive;
        if (interactive) {
            Minecraft mc = Minecraft.getInstance();
            ui.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
            ui.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
        }
    }

    /** 下一次 {@link #layout} 重建控件(宿主屏幕清过控件,输入框的编辑器要重新挂上);附言保留。 */
    private boolean stale;

    public void invalidate() {
        stale = true;
    }

    /** 画在哪一条请求上;没有在画的是 null。 */
    public ConsentRequestPayload shown() {
        return shown;
    }

    /**
     * 把这条请求摆在 {@code (x, y)}、宽 {@code w};请求或几何没变就沿用已建的控件(附言不丢)。
     *
     * @return 卡片高度
     */
    public int layout(ConsentRequestPayload request, int x, int y, int w) {
        if (!stale && request == shown && x == bx && y == by && w == bw) {
            return bh;
        }
        String keptNote = note != null && request == shown ? note.value() : "";
        stale = false;
        shown = request;
        bx = x;
        by = y;
        bw = w;
        ui.clear();
        lineLabels.clear();
        lineIrreversible.clear();
        int line = Minecraft.getInstance().font.lineHeight + 2;
        int ix = x + NumenStyle.PAD;
        int iw = w - NumenStyle.PAD * 2;
        int cy = y + NumenStyle.PAD;

        String name = NumenRoster.instance().name(request.companion());
        ui.add(new Label(I18n.get(ModLanguageData.Keys.CONSENT_TITLE, name == null ? "?" : name), Label.Role.PRIMARY))
                .setBounds(ix, cy, iw, line);
        cy += line;
        ui.add(new Label(request.reason(), Label.Role.SECONDARY)).setBounds(ix, cy, iw, line);
        cy += line;
        List<ConsentItem.Line> lines = request.lines();
        // 撤不回的那几行:行首留出一枚标的宽度(标在 render 里画)
        int markW = Minecraft.getInstance().font.width(I18n.get(ModLanguageData.Keys.CONSENT_IRREVERSIBLE)) + 8;
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) {
            boolean irreversible = lines.get(i).irreversible();
            Label label = ui.add(new Label(lines.get(i).text(),
                    irreversible ? Label.Role.PRIMARY : Label.Role.MUTED));
            label.setBounds(irreversible ? ix + markW : ix, cy, irreversible ? iw - markW : iw, line);
            lineLabels.add(label);
            lineIrreversible.add(irreversible);
            cy += line;
        }
        if (lines.size() > MAX_LINES) {
            ui.add(new Label(I18n.get(ModLanguageData.Keys.CONSENT_MORE, lines.size() - MAX_LINES), Label.Role.MUTED))
                    .setBounds(ix, cy, iw, line);
            cy += line;
        }
        countdown = ui.add(new Label("", Label.Role.MUTED));
        countdown.setBounds(ix, cy, iw, line);
        cy += line + GAP;
        if (interactive) {
            note = ui.add(new TextField(keptNote, v -> { })
                    .placeholder(I18n.get(ModLanguageData.Keys.CONSENT_NOTE)));
            note.setBounds(ix, cy, iw, NumenStyle.CONTROL_H);
            cy += NumenStyle.CONTROL_H + GAP;
            int bwEach = (iw - GAP * 2) / 3;
            ui.add(new Button(I18n.get(ModLanguageData.Keys.CONSENT_ALLOW), Button.Style.ACCENT,
                    () -> answer(ConsentAnswer.Decision.ALLOW_ONCE))).setBounds(ix, cy, bwEach, NumenStyle.CONTROL_H);
            ui.add(new Button(I18n.get(ModLanguageData.Keys.CONSENT_ALLOW_REMEMBER), Button.Style.NORMAL,
                    () -> answer(ConsentAnswer.Decision.ALLOW_REMEMBER)))
                    .setBounds(ix + bwEach + GAP, cy, bwEach, NumenStyle.CONTROL_H);
            ui.add(new Button(I18n.get(ModLanguageData.Keys.CONSENT_DENY), Button.Style.DANGER,
                    () -> answer(ConsentAnswer.Decision.DENY)))
                    .setBounds(ix + (bwEach + GAP) * 2, cy, iw - (bwEach + GAP) * 2, NumenStyle.CONTROL_H);
            cy += NumenStyle.CONTROL_H;
        } else {
            note = null;
            String key = com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER.getTranslatedKeyMessage().getString();
            ui.add(new Label(I18n.get(ModLanguageData.Keys.CONSENT_HUD_HINT, key), Label.Role.SECONDARY))
                    .setBounds(ix, cy, iw, line);
            cy += line;
        }
        bh = cy + NumenStyle.PAD - y;
        return bh;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, NumenTheme.Colors c) {
        if (shown == null) {
            return;
        }
        var level = Minecraft.getInstance().level;
        long left = level == null ? 0 : Math.max(0, shown.expiresAtGameTime() - level.getGameTime());
        countdown.setText(I18n.get(ModLanguageData.Keys.CONSENT_EXPIRES, (left + 19) / 20));
        McDrawSurface s = new McDrawSurface(g, Minecraft.getInstance().font);
        NumenStyle.fieldCard(s, bx, by, bw, bh, c.panelBg(), c.accent());
        // 撤不回的那几行:行首一枚醒目的标
        String mark = I18n.get(ModLanguageData.Keys.CONSENT_IRREVERSIBLE);
        for (int i = 0; i < lineLabels.size(); i++) {
            if (lineIrreversible.get(i)) {
                Badge.draw(s, mark, bx + NumenStyle.PAD, lineLabels.get(i).y() - 1, c.danger(), 0xFFFFFFFF);
            }
        }
        ui.render(s, c, mouseX, mouseY, net.minecraft.Util.getMillis());
    }

    public boolean contains(double mx, double my) {
        return shown != null && mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    /** 卡片范围内的点击都归它(点在空白处也不漏给下面的对话流)。 */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!interactive || !contains(mx, my)) {
            return false;
        }
        ui.mouseClicked(mx, my, button);
        return true;
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return interactive && note != null && note.isFocused() && ui.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return interactive && note != null && note.isFocused() && ui.charTyped(ch);
    }

    private void answer(ConsentAnswer.Decision decision) {
        if (shown != null) {
            ConsentCards.reply(shown, decision, note == null ? "" : note.value());
            shown = null;
        }
    }
}
