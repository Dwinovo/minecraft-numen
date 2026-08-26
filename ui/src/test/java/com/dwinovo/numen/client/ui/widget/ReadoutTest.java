package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 读数卡:高度随内容伸缩、数字右对齐、可选的条与警示不占空高度。 */
class ReadoutTest {

    private record Draw(String text, int x, int y) {}

    private static final class Recorder implements IDrawSurface {
        final List<Draw> texts = new ArrayList<>();
        int fills;
        @Override public void fillRect(int x, int y, int w, int h, int argb) { fills++; }
        @Override public void drawText(String t, int x, int y, int argb, boolean shadow) {
            texts.add(new Draw(t, x, y));
        }
        @Override public int textWidth(String t) { return t.length() * 6; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) { }
        @Override public void popScissor() { }
    }

    /** 可调的内容,好单独验每一块。 */
    private static final class Fake implements Readout.Content {
        String title = "标题";
        List<Readout.Line> lines = List.of(Readout.Line.of("甲", "1"));
        boolean withBar;
        String alert;

        @Override public String title() { return title; }
        @Override public List<Readout.Line> lines() { return lines; }
        @Override public List<Readout.Part> bar() {
            return withBar ? List.of(new Readout.Part(1, Readout.Tone.GOOD)) : List.of();
        }
        @Override public String alert() { return alert; }
    }

    private static NumenTheme.Colors colors() {
        return NumenTheme.DARK.colors();
    }

    @Test
    void heightGrowsWithLines() {
        Fake f = new Fake();
        int one = new Readout(f).preferredHeight();
        f.lines = List.of(Readout.Line.of("甲", "1"), Readout.Line.of("乙", "2"));
        assertTrue(new Readout(f).preferredHeight() > one);
    }

    @Test
    void optionalPartsCostNothingWhenAbsent() {
        // 服务商不报缓存就没有构成条;没有重付就没有警示——都不该占空高度
        Fake bare = new Fake();
        int bareH = new Readout(bare).preferredHeight();
        Fake full = new Fake();
        full.withBar = true;
        full.alert = "⚠ 有事";
        assertTrue(new Readout(full).preferredHeight() > bareH);
    }

    @Test
    void titlelessCardIsShorter() {
        Fake f = new Fake();
        int titled = new Readout(f).preferredHeight();
        f.title = null;
        assertTrue(new Readout(f).preferredHeight() < titled);
    }

    @Test
    void numbersAreRightAligned() {
        // 账要竖着比:两行数字位数不同,右边缘也得对齐
        Fake f = new Fake();
        f.title = null;
        f.lines = List.of(Readout.Line.of("甲", "1"), Readout.Line.of("乙", "1234567"));
        Readout r = new Readout(f);
        r.setBounds(0, 0, 200, r.preferredHeight());
        Recorder rec = new Recorder();
        r.render(rec, colors(), 0, 0, 0);
        int rightA = edge(rec, "1");
        int rightB = edge(rec, "1234567");
        assertEquals(rightA, rightB);
    }

    @Test
    void indentedLinesStartFurtherRight() {
        Fake f = new Fake();
        f.title = null;
        f.lines = List.of(Readout.Line.of("甲", "1"),
                Readout.Line.sub("乙", "2", null));
        Readout r = new Readout(f);
        r.setBounds(0, 0, 200, r.preferredHeight());
        Recorder rec = new Recorder();
        r.render(rec, colors(), 0, 0, 0);
        assertTrue(xOf(rec, "乙") > xOf(rec, "甲"));
    }

    @Test
    void noteRidesAlongWithTheNumber() {
        Fake f = new Fake();
        f.title = null;
        f.lines = List.of(Readout.Line.sub("命中", "100", "89.1%"));
        Readout r = new Readout(f);
        r.setBounds(0, 0, 200, r.preferredHeight());
        Recorder rec = new Recorder();
        r.render(rec, colors(), 0, 0, 0);
        assertTrue(rec.texts.stream().anyMatch(d -> d.text().contains("89.1%")));
    }

    @Test
    void measuredHeightFitsEverythingItDraws() {
        // 踩过的雷:测高时说"没有条",画的时候又画了,最后一行被卡边切掉。
        // 结构问题必须只用结构回答——这条盯着测高与实画不许分家。
        Fake f = new Fake();
        f.withBar = true;
        f.alert = "⚠ 有事";
        f.lines = List.of(Readout.Line.of("甲", "1"), Readout.Line.of("乙", "2"),
                Readout.Line.of("丙", "3"));
        Readout r = new Readout(f);
        int hh = r.preferredHeight();
        r.setBounds(0, 0, 200, hh);
        Recorder rec = new Recorder();
        r.render(rec, colors(), 0, 0, 0);
        int lowest = 0;
        for (Draw d : rec.texts) {
            lowest = Math.max(lowest, d.y() + rec.lineHeight());
        }
        assertTrue(lowest <= hh, "画到了 " + lowest + ",可卡只有 " + hh + " 高");
    }

    /** 某段文字的右边缘。 */
    private static int edge(Recorder rec, String needle) {
        for (Draw d : rec.texts) {
            if (d.text().equals(needle)) return d.x() + needle.length() * 6;
        }
        throw new AssertionError("没画出 " + needle);
    }

    private static int xOf(Recorder rec, String needle) {
        for (Draw d : rec.texts) {
            if (d.text().equals(needle)) return d.x();
        }
        throw new AssertionError("没画出 " + needle);
    }
}
