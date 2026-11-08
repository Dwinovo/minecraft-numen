package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 一排小标签({@link Badge}),装不下就折行。只展示,不接事件。普通标签是徽章色,{@code warn} 的标签用警示色。
 * 摆版时宿主要先知道占多高,所以折行的算法是静态的({@link #height}),控件画的时候用同一份。
 */
public final class TagRow extends Widget {

    public record Tag(String text, boolean warn) {}

    private static final int TAG_PAD = 6;
    private static final int GAP_X = 4;
    private static final int GAP_Y = 2;

    private final List<Tag> tags;

    public TagRow(List<Tag> tags) {
        this.tags = List.copyOf(tags);
    }

    /** 折行:每行尽量多放,放不下的另起一行(单个标签再宽也独占一行)。 */
    private static List<List<Tag>> wrap(ToIntFunction<String> width, List<Tag> tags, int w) {
        List<List<Tag>> lines = new ArrayList<>();
        List<Tag> line = new ArrayList<>();
        int used = 0;
        for (Tag t : tags) {
            int tw = width.applyAsInt(t.text()) + TAG_PAD;
            if (!line.isEmpty() && used + GAP_X + tw > w) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
            }
            used += (line.isEmpty() ? 0 : GAP_X) + tw;
            line.add(t);
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    /** 这些标签在宽 {@code w} 里摆下要多高;没有标签是 0。 */
    public static int height(ToIntFunction<String> width, int lineHeight, List<Tag> tags, int w) {
        int n = wrap(width, tags, w).size();
        return n == 0 ? 0 : n * lineHeight + (n - 1) * GAP_Y;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int ly = y;
        for (List<Tag> line : wrap(s::textWidth, tags, w)) {
            int lx = x;
            for (Tag t : line) {
                lx += (t.warn() ? Badge.draw(s, t.text(), lx, ly, c.toastWarnBg(), c.warning())
                        : Badge.draw(s, c, t.text(), lx, ly)) + GAP_X;
            }
            ly += s.lineHeight() + GAP_Y;
        }
    }
}
