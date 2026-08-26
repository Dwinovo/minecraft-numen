package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.StackedBar;

import java.util.ArrayList;
import java.util.List;

/**
 * 读数卡:一条构成条 + 几行"左标题右数字" + 一条可选警示。没有任何一行可以按——
 * 它是仪表不是表单。
 *
 * <p>数字<b>右对齐</b>:账要竖着比才看得出量级,左对齐的数字位数一变就错开。
 *
 * <h2>内容只报语气,不报颜色</h2>
 * {@link Content} 里没有一个 ARGB。原因不是洁癖:高度要在画之前算出来(弹层底边对齐
 * 输入框、往上长),如果"有没有条"这类结构问题得先拿到主题色才能回答,测高与实画就成了
 * 两条路——它们迟早会给出不同的答案,卡就短一截,最后一行被边框切掉。
 * 结构问题用结构回答,颜色在画的那一刻才由主题定。
 */
public final class Readout extends Popup {

    /** 语气:内容说"这是好的/中性的/要留神的",卡按主题翻成颜色。 */
    public enum Tone {
        GOOD, PLAIN, WARN
    }

    /** 构成条的一段。 */
    public record Part(long value, Tone tone) {}

    /**
     * 一行。{@code indent} 是明细行的缩进,{@code note} 是数字后面的尾注(百分比之类),
     * {@code tone} 决定数字的颜色。
     */
    public record Line(String label, String value, String note, boolean indent, Tone tone) {

        public static Line of(String label, String value) {
            return new Line(label, value, null, false, Tone.PLAIN);
        }

        public static Line sub(String label, String value, String note) {
            return new Line(label, value, note, true, Tone.PLAIN);
        }

        public static Line toned(String label, String value, Tone tone) {
            return new Line(label, value, null, false, tone);
        }
    }

    /** 卡里的内容。全部是结构,不含颜色。 */
    public interface Content {

        /** 标题行;{@code null} = 不画。 */
        String title();

        List<Line> lines();

        /** 构成条的分段;空 = 不画条。 */
        default List<Part> bar() {
            return List.of();
        }

        /** 底部那条警示;{@code null} = 不画。 */
        default String alert() {
            return null;
        }
    }

    private static final int PAD = 4;
    private static final int TITLE_H = 11;
    private static final int LINE_H = 11;
    private static final int BAR_H = 6;
    private static final int BAR_GAP = 5;
    private static final int ALERT_H = 16;

    private final Content content;

    public Readout(Content content) {
        this.content = content;
    }

    @Override
    public int preferredHeight() {
        int h = PAD * 2 + content.lines().size() * LINE_H;
        if (content.title() != null) h += TITLE_H;
        if (!content.bar().isEmpty()) h += BAR_H + BAR_GAP;
        if (content.alert() != null) h += ALERT_H + 2;
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

        List<Part> parts = content.bar();
        if (!parts.isEmpty()) {
            List<StackedBar.Segment> segs = new ArrayList<>(parts.size());
            for (Part part : parts) {
                segs.add(new StackedBar.Segment(part.value(), color(c, part.tone())));
            }
            StackedBar.draw(s, x + PAD, iy, inner, BAR_H, c.inputBg(), segs);
            iy += BAR_H + BAR_GAP;
        }

        for (Line line : content.lines()) {
            int lx = x + PAD + (line.indent() ? 8 : 0);
            int textY = iy + (LINE_H - s.lineHeight()) / 2;
            s.drawText(line.label(), lx, textY,
                    line.indent() ? c.textMuted() : c.textSecondary(), false);
            String right = line.note() == null ? line.value() : line.value() + "  " + line.note();
            s.drawText(right, x + w - PAD - s.textWidth(right), textY,
                    line.tone() == Tone.PLAIN ? c.textPrimary() : color(c, line.tone()), false);
            iy += LINE_H;
        }

        String alert = content.alert();
        if (alert != null) {
            iy += 2;
            s.fillRoundRect(x + PAD, iy, inner, ALERT_H, NumenStyle.RADIUS_SMALL, c.toastWarnBg());
            s.drawText(alert, x + PAD + 5, iy + (ALERT_H - s.lineHeight()) / 2, c.warning(), false);
        }
    }

    private static int color(NumenTheme.Colors c, Tone tone) {
        return switch (tone) {
            case GOOD -> c.success();
            case WARN -> c.danger();
            case PLAIN -> c.textMuted();
        };
    }
}
