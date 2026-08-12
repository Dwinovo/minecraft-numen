package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactTextWidgetsTest {

    private static final class Surface implements IDrawSurface {
        private final List<Draw> draws = new ArrayList<>();

        @Override public void fillRect(int x, int y, int w, int h, int argb) {}
        @Override public void drawText(String text, int x, int y, int argb, boolean shadow) {
            draws.add(new Draw(text, x));
        }
        @Override public int textWidth(String text) { return text.codePointCount(0, text.length()) * 6; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) {}
        @Override public void popScissor() {}
    }

    private record Draw(String text, int x) {}

    @Test
    void buttonTextNeverExceedsItsBounds() {
        Surface surface = new Surface();
        Button button = new Button("numen.provider.add", Button.Style.ACCENT, () -> {});
        button.setBounds(10, 10, 56, 14);
        button.render(surface, NumenTheme.DARK.colors(), 0, 0, 0);

        Draw draw = surface.draws.get(0);
        assertTrue(draw.x() >= 10);
        assertTrue(draw.x() + surface.textWidth(draw.text()) <= 66);
        assertTrue(draw.text().endsWith("..."));
    }

    @Test
    void labelAndValueStayInsideAssignedWidth() {
        Surface surface = new Surface();
        Label label = new Label("numen.settings.nav.brain", Label.Role.PRIMARY);
        label.setBounds(4, 4, 48, 9);
        label.render(surface, NumenTheme.DARK.colors(), 0, 0, 0);

        ValueRow row = new ValueRow("numen.brain.endpoint", () -> "http://127.0.0.1:8765/mcp");
        row.setBounds(4, 20, 120, ValueRow.HEIGHT);
        row.render(surface, NumenTheme.DARK.colors(), 0, 0, 0);

        assertEquals(3, surface.draws.size());
        for (Draw draw : surface.draws) {
            assertTrue(draw.x() + surface.textWidth(draw.text()) <= 124);
        }
    }

    @Test
    void veryNarrowWidgetsDoNotDrawTextOutsideTheirBounds() {
        Surface surface = new Surface();
        Button button = new Button("设置", Button.Style.GHOST, () -> {});
        button.setBounds(8, 8, 5, 14);
        button.render(surface, NumenTheme.DARK.colors(), 0, 0, 0);

        ValueRow row = new ValueRow("Access token", () -> "secret");
        row.setBounds(8, 24, 30, ValueRow.HEIGHT);
        row.render(surface, NumenTheme.DARK.colors(), 0, 0, 0);

        assertEquals(2, surface.draws.size());
        for (Draw draw : surface.draws) {
            assertTrue(draw.x() >= 8);
            assertTrue(draw.x() + surface.textWidth(draw.text()) <= 38);
        }
    }

    @Test
    void ellipsisDoesNotSplitUnicodeCodePoints() {
        Surface surface = new Surface();
        String shown = TextClip.ellipsize(surface, "设置😀面板", 24);

        assertTrue(surface.textWidth(shown) <= 24);
        assertTrue(shown.endsWith("..."));
        assertFalse(hasUnpairedSurrogate(shown));
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }
}
