package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;

/**
 * 折叠节的抬头:一个三角 + 标题 + 一道延到行尾的细线,点一下开合。
 *
 * <p>默认收着,把只有懂行的人才要动的东西藏在后面——新手看到的是一页能看完的东西,
 * 想细调的人点开就是。三角的朝向就是"再点一下会往哪走":收着时朝右,展开时朝下。
 *
 * <p>控件自己不记开没开:状态在宿主手里({@code expanded} 传进来,点击时回调宿主),
 * 因为里面有哪些行、占多高只有宿主算得出来,状态放两处必然对不上。
 */
public final class Disclosure extends Widget {

    /** 三角那一格的边长(朝右时宽 4 高 7,朝下时宽 7 高 4)。 */
    private static final int ARROW = 7;
    /** 三角与标题之间。 */
    private static final int ARROW_GAP = 5;
    /** 标题与那道细线之间。 */
    private static final int RULE_GAP = 6;

    private final String title;
    private final boolean expanded;
    private final Runnable onToggle;

    public Disclosure(String title, boolean expanded, Runnable onToggle) {
        this.title = title == null ? "" : title;
        this.expanded = expanded;
        this.onToggle = onToggle;
    }

    public boolean expanded() {
        return expanded;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hot = contains(mouseX, mouseY);
        int fg = hot ? c.textPrimary() : c.textMuted();

        if (expanded) {   // 朝下:每往下一行窄两格
            int ay = y + (h - 4) / 2;
            for (int i = 0; i < 4; i++) {
                s.fillRect(x + i, ay + i, ARROW - 2 * i, 1, fg);
            }
        } else {          // 朝右:每往右一列矮两格
            int ay = y + (h - ARROW) / 2;
            for (int i = 0; i < 4; i++) {
                s.fillRect(x + i, ay + i, 1, ARROW - 2 * i, fg);
            }
        }

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
        onToggle.run();
        return true;
    }
}
