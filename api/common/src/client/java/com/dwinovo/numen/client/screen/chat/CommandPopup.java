package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.client.command.Completion;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.List;

/**
 * 输入框<b>上方</b>的斜杠命令补全弹层。
 *
 * <p>纯绘制:收一份候选和一个选中下标就画,不认识命令是什么、也不管键盘。往上长是因为
 * 输入框在面板底部——往下没地方,而且往上正好压在对话流上,视线不用离开正在打的字。
 */
final class CommandPopup {

    /** 一行的高度。比正文行距略宽一点,免得两行字挤在一起。 */
    private static final int ROW_H = 12;
    private static final int PAD = 3;
    /** 会动她的命令,行首这个记号。纯提示,见 {@code ChatCommand#touchesContext}。 */
    private static final String MARK = "›";

    private CommandPopup() {}

    /** 可见行数(候选多于这个数就滚动,让选中的那行始终在窗内)。 */
    static int visibleRows(int total) {
        return Math.min(total, NumenStyle.POPUP_MAX_ROWS);
    }

    /** 弹层总高;没有候选返回 0。 */
    static int height(int total) {
        int rows = visibleRows(total);
        return rows == 0 ? 0 : rows * ROW_H + PAD * 2;
    }

    /**
     * 画在输入框上方。
     *
     * @param bottom 输入框的顶边——弹层的底边贴着它
     */
    static void render(IDrawSurface s, NumenTheme.Colors c, List<Completion> rows,
                       int selected, int x, int bottom, int w) {
        int total = rows.size();
        int shown = visibleRows(total);
        if (shown == 0) {
            return;
        }
        int h = height(total);
        int y = bottom - h;
        NumenStyle.fieldCard(s, x, y, w, h, c.panelBg(), c.inputBorder());

        int first = windowStart(selected, total, shown);
        for (int i = 0; i < shown; i++) {
            drawRow(s, c, rows.get(first + i), first + i == selected,
                    x + PAD, y + PAD + i * ROW_H, w - PAD * 2);
        }
    }

    /** 让选中行落在窗内的起始下标。 */
    private static int windowStart(int selected, int total, int shown) {
        if (total <= shown) {
            return 0;
        }
        int start = selected - shown / 2;
        return Math.max(0, Math.min(start, total - shown));
    }

    private static void drawRow(IDrawSurface s, NumenTheme.Colors c, Completion row,
                                boolean picked, int x, int y, int w) {
        if (picked) {
            s.fillRoundRect(x - 1, y - 1, w + 2, ROW_H, NumenStyle.RADIUS_SMALL, c.selected());
        }
        int textY = y + (ROW_H - s.lineHeight()) / 2;
        int left = x;
        if (row.touchesContext()) {
            s.drawText(MARK, left, textY, row.enabled() ? c.accent() : c.textMuted(), false);
        }
        left += s.textWidth(MARK) + 2;

        int labelColor = !row.enabled() ? c.textMuted()
                : picked ? c.textPrimary() : c.textSecondary();
        s.drawText(row.label(), left, textY, labelColor, false);

        // 说明靠右,剩多少画多少;不可用时这里是理由,所以宁可挤掉 label 也要留给它。
        String note = row.note();
        if (note.isEmpty()) {
            return;
        }
        int labelEnd = left + s.textWidth(row.label()) + 6;
        int room = x + w - labelEnd;
        if (room <= 0) {
            return;
        }
        String clipped = clip(s, note, room);
        s.drawText(clipped, x + w - s.textWidth(clipped), textY,
                row.enabled() ? c.textMuted() : c.danger(), false);
    }

    private static String clip(IDrawSurface s, String text, int maxW) {
        if (s.textWidth(text) <= maxW) {
            return text;
        }
        String out = text;
        while (out.length() > 1 && s.textWidth(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
