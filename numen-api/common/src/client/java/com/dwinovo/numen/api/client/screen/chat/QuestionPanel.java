package com.dwinovo.numen.api.client.screen.chat;

import com.dwinovo.numen.api.agent.tool.Asking;
import com.dwinovo.numen.api.agent.tool.Questions.Question;
import com.dwinovo.numen.api.client.screen.Nb;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 她的提问在主人面前的样子({@link Asking} 的画面与点击):标签与第几个、问句、编号选项(名字后面跟淡色的说明)、
 * 多选的"下一步"、"聊聊这个";问到最后是确认那一步,列出每题的答案,提交与"聊聊这个"。"其他回答…"那一行是输入行自己的输入框,
 * 摆在这块面板正下方,不在这里画。面板按内容取自然高度、没有滚动条;高度由 {@link #height} 报给输入行,对话流随之让位。
 *
 * <p>每帧 {@link #layout} 按此刻的问题与宽排一遍行,{@link #height}、{@link #render}、{@link #hit} 读同一份排版,
 * 画与点不会各算各的。
 */
final class QuestionPanel {

    /** 点中了什么。 */
    enum Action { OPTION, NEXT, SUBMIT, CHAT }

    /** 点中的位置:动作与第几个选项(只有 {@link Action#OPTION} 用)。 */
    record Hit(Action action, int option) {}

    private enum Kind { TITLE, TEXT, OPTION, NEXT, SUBMIT, CHAT, REVIEW }

    private record Row(Kind kind, int index, int y, int h, List<FormattedCharSequence> lines, String text) {}

    private static final int PAD = 6;
    private static final int GAP = 3;
    private static final int ROW_PAD = 2;
    private static final int NUMBER_W = 12;
    private static final int BOX = 7;

    private final List<Row> rows = new ArrayList<>();
    private int height;
    private int x, w;

    /** 排版:此刻的问题、此刻的宽({@code x} 是面板左缘)。 */
    void layout(Font font, Asking asking, NumenTheme.Colors c, int x, int w) {
        this.x = x;
        this.w = w;
        rows.clear();
        int lineH = font.lineHeight + 2;
        int inner = w - PAD * 2;
        int y = GAP;
        Question q = asking.current();
        if (q == null) {
            y = add(font, Kind.TITLE, -1, y, lineH, I18n.get(Keys.QUESTION_REVIEW), inner, 0);
            for (int i = 0; i < asking.questions().size(); i++) {
                String line = "· " + asking.questions().get(i).question() + " → " + asking.answer(i);
                y = add(font, Kind.REVIEW, i, y, lineH, line, inner, c.textSecondary());
            }
            y += GAP;
            y = add(font, Kind.SUBMIT, -1, y, lineH + ROW_PAD * 2, I18n.get(Keys.QUESTION_SUBMIT), inner, 0);
        } else {
            y = add(font, Kind.TITLE, -1, y, lineH, q.header(), inner, 0);
            y = add(font, Kind.TEXT, -1, y, lineH, q.question(), inner, c.textPrimary());
            y += GAP;
            for (int i = 0; i < q.options().size(); i++) {
                y = add(font, Kind.OPTION, i, y, lineH + ROW_PAD * 2, q.options().get(i).label(), inner, 0);
            }
            if (q.multiSelect()) {
                y = add(font, Kind.NEXT, -1, y, lineH + ROW_PAD * 2, I18n.get(Keys.QUESTION_NEXT), inner, 0);
            }
        }
        y = add(font, Kind.CHAT, -1, y, lineH + ROW_PAD * 2, I18n.get(Keys.QUESTION_CHAT), inner, 0);
        height = y + GAP;
    }

    private int add(Font font, Kind kind, int index, int y, int rowH, String text, int inner, int color) {
        List<FormattedCharSequence> lines = List.of();
        int h = rowH;
        if (kind == Kind.TEXT || kind == Kind.REVIEW) {
            lines = font.split(Nb.colored(text, color), inner);
            h = Math.max(1, lines.size()) * rowH;
        }
        rows.add(new Row(kind, index, y, h, lines, text));
        return y + h;
    }

    /** 面板要多高(自然高度,不含"其他回答…"那一行)。 */
    int height() {
        return height;
    }

    /** 画在 {@code bottom} 之上;{@code shown} 是此刻露出多高(长出与收起时比 {@link #height} 小,裁着画)。 */
    void render(GuiGraphics g, Font font, Asking asking, NumenTheme.Colors c, int bottom, int shown, int mouseX, int mouseY) {
        int top = bottom - height;
        int clipTop = bottom - shown;
        g.enableScissor(x, clipTop, x + w, bottom);
        g.fill(x, clipTop, x + w, bottom, c.inputBg());
        g.fill(x, bottom - height, x + w, bottom - height + 1, c.divider());
        int lineH = font.lineHeight + 2;
        for (Row row : rows) {
            int y = top + row.y();
            boolean hot = clickable(row.kind()) && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + row.h();
            if (hot) {
                g.fill(x, y, x + w, y + row.h(), c.hover());
            }
            int tx = x + PAD;
            switch (row.kind()) {
                case TITLE -> title(g, font, asking, c, row, tx, y, lineH);
                case TEXT, REVIEW -> {
                    for (int i = 0; i < row.lines().size(); i++) {
                        Nb.text(g, font, row.lines().get(i), tx, y + i * lineH + 1);
                    }
                }
                case OPTION -> option(g, font, asking, c, row, tx, y);
                case NEXT -> Nb.text(g, font, row.text(), tx + NUMBER_W, y + ROW_PAD + 1, c.accent());
                case SUBMIT -> {
                    Nb.text(g, font, "1", tx, y + ROW_PAD + 1, c.accent());
                    Nb.text(g, font, row.text(), tx + NUMBER_W, y + ROW_PAD + 1, c.textPrimary());
                }
                case CHAT -> Nb.text(g, font, row.text(), tx + NUMBER_W, y + ROW_PAD + 1, c.textSecondary());
            }
        }
        g.disableScissor();
    }

    private void title(GuiGraphics g, Font font, Asking asking, NumenTheme.Colors c, Row row, int tx, int y, int lineH) {
        if (asking.current() == null) {
            Nb.text(g, font, row.text(), tx, y + 1, c.accent());
        } else {
            int tagW = font.width(row.text()) + 8;
            g.fill(tx, y, tx + tagW, y + lineH, c.badgeBg());
            Nb.text(g, font, row.text(), tx + 4, y + 1, c.badgeText());
        }
        if (!asking.direct()) {
            String progress = Math.min(asking.step() + 1, asking.questions().size()) + "/" + asking.questions().size();
            Nb.text(g, font, progress, x + w - PAD - font.width(progress), y + 1, c.textMuted());
        }
    }

    private void option(GuiGraphics g, Font font, Asking asking, NumenTheme.Colors c, Row row, int tx, int y) {
        Question q = asking.current();
        int i = row.index();
        boolean picked = asking.picked(asking.step(), i);
        int ty = y + ROW_PAD + 1;
        Nb.text(g, font, Integer.toString(i + 1), tx, ty, c.accent());
        int lx = tx + NUMBER_W;
        if (q.multiSelect()) {
            int by = y + (row.h() - BOX) / 2;
            Nb.border(g, lx, by, BOX, BOX, 1, picked ? c.accent() : c.textMuted());
            if (picked) {
                g.fill(lx + 2, by + 2, lx + BOX - 2, by + BOX - 2, c.accent());
            }
            lx += BOX + 4;
        }
        String label = row.text();
        Nb.text(g, font, label, lx, ty, picked && !q.multiSelect() ? c.accent() : c.textPrimary());
        int room = x + w - PAD - (lx + font.width(label) + 8);
        String description = q.options().get(i).description();
        if (room > 16 && !description.isBlank()) {
            Nb.text(g, font, Nb.clip(font, description, room), lx + font.width(label) + 8, ty, c.textMuted());
        }
    }

    private static boolean clickable(Kind kind) {
        return kind == Kind.OPTION || kind == Kind.NEXT || kind == Kind.SUBMIT || kind == Kind.CHAT;
    }

    /** 点在 {@code (mx, my)} 上是什么;{@code bottom} 同 {@link #render};没点中任何一行是 null。 */
    Hit hit(double mx, double my, int bottom) {
        if (mx < x || mx >= x + w) {
            return null;
        }
        int top = bottom - height;
        for (Row row : rows) {
            int y = top + row.y();
            if (clickable(row.kind()) && my >= y && my < y + row.h()) {
                return switch (row.kind()) {
                    case OPTION -> new Hit(Action.OPTION, row.index());
                    case NEXT -> new Hit(Action.NEXT, -1);
                    case SUBMIT -> new Hit(Action.SUBMIT, -1);
                    default -> new Hit(Action.CHAT, -1);
                };
            }
        }
        return null;
    }
}
