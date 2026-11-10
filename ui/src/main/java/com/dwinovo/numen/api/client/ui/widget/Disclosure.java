package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;

/**
 * 折叠节的抬头:一个三角 + 标题 + 一道延到行尾的细线,点一下开合。
 *
 * <p>默认收着,把只有懂行的人才要动的东西藏在后面——新手看到的是一页能看完的东西,
 * 想细调的人点开就是。三角跟着开合转:收着朝右,展开朝下,中间是转到一半的样子。
 *
 * <p>控件自己不记开没开:状态是宿主手里的 {@link Reveal},点击翻它;里面有哪些行、占多高只有宿主算得出来,
 * 宿主照同一个 {@link Reveal#value()} 定那一截露出多高,三角与高度同步。
 */
public final class Disclosure extends Widget {

    /** 三角那一格的边长(见 {@link TurnArrow})。 */
    private static final int ARROW = TurnArrow.SIZE;
    /** 三角与标题之间。 */
    private static final int ARROW_GAP = 5;
    /** 标题与那道细线之间。 */
    private static final int RULE_GAP = 6;

    private final String title;
    private final Reveal reveal;

    public Disclosure(String title, Reveal reveal) {
        this.title = title == null ? "" : title;
        this.reveal = reveal;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hot = contains(mouseX, mouseY);
        int fg = hot ? c.textPrimary() : c.textSecondary();

        TurnArrow.draw(s, x + ARROW / 2, y + h / 2, fg, reveal.value());

        int tx = x + ARROW + ARROW_GAP;
        int ty = y + (h - s.lineHeight()) / 2;
        String text = TextClip.fit(s, title, Math.max(0, w - (tx - x)));
        s.drawText(text, tx, ty, fg, false);

        // 标题右边那道细线:把这一节和上面的内容划开,又不用再套一层框
        int ruleX = tx + s.textWidth(text) + RULE_GAP;
        if (ruleX < x + w) {
            s.fillRect(ruleX, y + h / 2, x + w - ruleX, 1, c.divider());
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        reveal.toggle();
        return true;
    }
}
