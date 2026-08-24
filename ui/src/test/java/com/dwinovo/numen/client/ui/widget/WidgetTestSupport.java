package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.ArrayList;
import java.util.List;

/** 控件测试公用件:假画布(每字符 6px)+ 主题常量。 */
final class WidgetTestSupport {

    private WidgetTestSupport() {}

    static final NumenTheme.Colors C = NumenTheme.DARK.colors();

    static final class FakeSurface implements IDrawSurface {
        final List<String> texts = new ArrayList<>();
        final List<int[]> rects = new ArrayList<>();
        int scissorDepth;
        int maxScissorDepth;

        @Override public void fillRect(int x, int y, int w, int h, int argb) {
            rects.add(new int[]{x, y, w, h});
        }
        @Override public void drawText(String t, int x, int y, int argb, boolean shadow) {
            texts.add(t);
        }
        @Override public int textWidth(String t) { return t.length() * 6; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) {
            scissorDepth++;
            maxScissorDepth = Math.max(maxScissorDepth, scissorDepth);
        }
        @Override public void popScissor() { scissorDepth--; }

        void reset() { texts.clear(); rects.clear(); }
    }
}
