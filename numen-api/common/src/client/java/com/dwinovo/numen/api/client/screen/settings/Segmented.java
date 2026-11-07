package com.dwinovo.numen.api.client.screen.settings;

import com.dwinovo.numen.api.client.ui.Animation;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;
import com.dwinovo.numen.api.client.ui.widget.Widget;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * 几选一的分段钮(权限页的同伴模式、加规则表单里的判决):一圈细描边里排开几格,选中那格的强调色块滑过去。
 * 点哪格,色块当场滑向哪格并通知 {@code onPick};{@code current} 是别处说的"现在该选哪格"(服务端存的模式、
 * 表单草稿),它变了色块也跟着滑——点完服务端还没答复的这一个来回里,界面不卡着旧样子。
 *
 * <p>每格等宽,宽按最长的字给({@link #widthFor}),字不被截。
 */
final class Segmented extends Widget {

    private static final int CELL_PAD = 12;

    private final List<String> labels;
    private final IntSupplier current;
    private final IntConsumer onPick;
    /** 色块此刻的位置(格数,可以是小数);还没画过是 -1。 */
    private float knob = -1;
    /** 色块要去的格。 */
    private int shown = -1;
    private int lastCurrent = -1;
    private long lastMs = -1;

    Segmented(List<String> labels, IntSupplier current, IntConsumer onPick) {
        this.labels = labels;
        this.current = current;
        this.onPick = onPick;
    }

    /** 这几个字排成分段钮需要的宽:每格取最宽的字加内边距,再加两侧描边。 */
    static int widthFor(List<String> labels, ToIntFunction<String> textWidth) {
        int cell = labels.stream().mapToInt(textWidth).max().orElse(0) + CELL_PAD;
        return cell * labels.size() + 2;
    }

    private int cellW() {
        return (w - 2) / labels.size();
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int now = current.getAsInt();
        if (now != lastCurrent) {
            lastCurrent = now;
            shown = now;
        }
        long dt = lastMs < 0 ? 16 : nowMs - lastMs;
        lastMs = nowMs;
        knob = knob < 0 ? shown : Animation.lerpTo(knob, shown, Math.min(1f, dt / 16f * 0.35f), 0.01f);

        s.fillRect(x, y, w, h, c.inputBorder());
        s.fillRect(x + 1, y + 1, w - 2, h - 2, c.inputBg());
        int cell = cellW();
        int hovered = enabled && contains(mouseX, mouseY) ? Math.min(labels.size() - 1, (mouseX - x - 1) / cell) : -1;
        if (hovered >= 0 && hovered != shown) {
            s.fillRect(x + 1 + hovered * cell, y + 1, cell, h - 2, c.hover());
        }
        s.fillRect(x + 1 + Math.round(knob * cell), y + 1, cell, h - 2, c.accent());
        for (int i = 0; i < labels.size(); i++) {
            String text = TextClip.fit(s, labels.get(i), cell - 4);
            boolean on = Math.abs(i - knob) < 0.5f;
            s.drawText(text, x + 1 + i * cell + (cell - s.textWidth(text)) / 2, y + (h - s.lineHeight()) / 2 + 1,
                    on ? 0xFFFFFFFF : c.textSecondary(), false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !enabled || !contains(mx, my)) {
            return false;
        }
        int pick = Math.max(0, Math.min(labels.size() - 1, (int) (mx - x - 1) / cellW()));
        shown = pick;
        onPick.accept(pick);
        return true;
    }
}
