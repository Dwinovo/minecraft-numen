package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.StackedBar;

import java.util.List;
import java.util.function.Supplier;

/**
 * 读数卡:一条构成条 + 几行"左标题右数字"。没有任何一行可以按——它是仪表不是表单。
 *
 * <p>数字<b>右对齐</b>:账要竖着比才看得出量级,左对齐的数字位数一变就错开。
 *
 * <p>取数走 {@link Supplier},每帧现取:这类数据随时在变,构造时捕获会把过期的数字
 * 钉死在屏幕上。
 */
public final class Readout extends Popup {

    /**
     * 一行。{@code indent} 是明细行的缩进,{@code note} 是数字后面的尾注(百分比之类),
     * {@code color} 为 {@code null} 时用常规文本色。
     */
    public record Line(String label, String value, String note, boolean indent, Integer color) {

        public static Line of(String label, String value) {
            return new Line(label, value, null, false, null);
        }

        public static Line sub(String label, String value, String note, Integer color) {
            return new Line(label, value, note, true, color);
        }
    }

    /** 卡里的内容。 */
    public interface Content {

        /** 标题行;{@code null} = 不画。 */
        String title();

        List<Line> lines();

        /** 构成条的分段;空 = 不画条。 */
        List<StackedBar.Segment> bar(NumenTheme.Colors c);

        /** 底部那条警示;{@code null} = 不画。 */
        default String alert(NumenTheme.Colors c) {
            return null;
        }
    }

    private static final int PAD = 4;
    private static final int TITLE_H = 11;
    private static final int LINE_H = 11;
    private static final int BAR_H = 6;
    private static final int ALERT_H = 22;

    private final Content content;

    public Readout(Content content) {
        this.content = content;
    }

    @Override
    public int preferredHeight() {
        int h = PAD * 2 + content.lines().size() * LINE_H;
        if (content.title() != null) h += TITLE_H;
        if (!content.bar(null).isEmpty()) h += BAR_H + 5;
        if (content.alert(null) != null) h += ALERT_H;
        return h;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        NumenStyle.fieldCard(s, x, y, w, h, c.panelBg(), c.accent());
        int iy = y + PAD;
        int inner = w - PAD * 2;

        String title = content.title();
        if (title != null) {
            s.drawText(title, x + PAD, iy, c.textSecondary(), false);
            iy += TITLE_H;
        }

        List<StackedBar.Segment> bar = content.bar(c);
        if (!bar.isEmpty()) {
            StackedBar.draw(s, x + PAD, iy, inner, BAR_H, c.inputBg(), bar);
            iy += BAR_H + 5;
        }

        for (Line line : content.lines()) {
            int lx = x + PAD + (line.indent() ? 8 : 0);
            int textY = iy + (LINE_H - s.lineHeight()) / 2;
            s.drawText(line.label(), lx, textY,
                    line.indent() ? c.textMuted() : c.textSecondary(), false);
            String right = line.note() == null ? line.value() : line.value() + "  " + line.note();
            s.drawText(right, x + w - PAD - s.textWidth(right), textY,
                    line.color() == null ? c.textPrimary() : line.color(), false);
            iy += LINE_H;
        }

        String alert = content.alert(c);
        if (alert != null) {
            s.fillRoundRect(x + PAD, iy, inner, ALERT_H - 4,
                    NumenStyle.RADIUS_SMALL, c.toastWarnBg());
            s.drawText(alert, x + PAD + 5, iy + (ALERT_H - 4 - s.lineHeight()) / 2,
                    c.warning(), false);
        }
    }
}
