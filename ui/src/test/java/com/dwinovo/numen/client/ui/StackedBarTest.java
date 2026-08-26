package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 堆叠条:比例、累计取整、非零段必可见、不溢出。 */
class StackedBarTest {

    private record Fill(int x, int y, int w, int h, int argb) {}

    /** 记下每一次填充,好断言几何。 */
    private static final class Recorder implements IDrawSurface {
        final List<Fill> fills = new ArrayList<>();
        @Override public void fillRect(int x, int y, int w, int h, int argb) {
            fills.add(new Fill(x, y, w, h, argb));
        }
        @Override public void drawText(String t, int x, int y, int argb, boolean shadow) { }
        @Override public int textWidth(String t) { return t.length() * 6; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) { }
        @Override public void popScissor() { }
    }

    private static final int TRACK = 0xFF111111;
    private static final int A = 0xFFAAAAAA;
    private static final int B = 0xFFBBBBBB;

    /** 底槽之后的那些填充才是段。 */
    private static List<Fill> segmentsOf(Recorder r) {
        return r.fills.subList(1, r.fills.size());
    }

    @Test
    void trackIsDrawnEvenWithNoSegments() {
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 100, 8, TRACK, List.of());
        assertEquals(1, r.fills.size());
        assertEquals(TRACK, r.fills.get(0).argb());
    }

    @Test
    void twoSegmentsSplitByProportion() {
        Recorder r = new Recorder();
        StackedBar.draw(r, 10, 5, 100, 8, TRACK,
                List.of(new StackedBar.Segment(75, A), new StackedBar.Segment(25, B)));
        List<Fill> segs = segmentsOf(r);
        assertEquals(2, segs.size());
        assertEquals(10, segs.get(0).x());
        assertEquals(75, segs.get(0).w());
        assertEquals(85, segs.get(1).x());
        assertEquals(25, segs.get(1).w());
    }

    @Test
    void segmentsNeverOverflowTheBar() {
        // 三段除不尽:各段各自四舍五入再相加会溢出,对累计量算才不会
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 100, 8, TRACK, List.of(
                new StackedBar.Segment(1, A), new StackedBar.Segment(1, B),
                new StackedBar.Segment(1, A)));
        int right = 0;
        for (Fill f : segmentsOf(r)) {
            right = Math.max(right, f.x() + f.w());
        }
        assertTrue(right <= 100, "条尾超出了 100:" + right);
    }

    @Test
    void aTinyButNonZeroSegmentStaysVisible() {
        // 一万比一:按比例是 0 像素,但"有一点"画成"没有"就是画面在说谎
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 100, 8, TRACK,
                List.of(new StackedBar.Segment(10_000, A), new StackedBar.Segment(1, B)));
        List<Fill> segs = segmentsOf(r);
        assertEquals(2, segs.size());
        assertTrue(segs.get(1).w() >= StackedBar.MIN_VISIBLE_PX, "细段被抹掉了");
    }

    @Test
    void zeroValueSegmentsTakeNoSpace() {
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 100, 8, TRACK,
                List.of(new StackedBar.Segment(0, A), new StackedBar.Segment(50, B)));
        List<Fill> segs = segmentsOf(r);
        assertEquals(1, segs.size());
        assertEquals(B, segs.get(0).argb());
        assertEquals(100, segs.get(0).w());   // 唯一的非零段占满
    }

    @Test
    void allZeroDrawsOnlyTheTrack() {
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 100, 8, TRACK,
                List.of(new StackedBar.Segment(0, A), new StackedBar.Segment(0, B)));
        assertEquals(1, r.fills.size());
    }

    @Test
    void degenerateSizesDrawNothing() {
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 0, 8, TRACK, List.of(new StackedBar.Segment(1, A)));
        StackedBar.draw(r, 0, 0, 100, 0, TRACK, List.of(new StackedBar.Segment(1, A)));
        assertEquals(0, r.fills.size());
    }

    @Test
    void explicitTotalLetsOneSegmentBeAFraction() {
        // 水位条:只有"已用"一段,分母是容量。拿各段之和当分母的话它永远画满
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 100, 6, TRACK, 100, List.of(new StackedBar.Segment(30, A)));
        assertEquals(30, segmentsOf(r).get(0).w());
    }

    @Test
    void summedTotalWouldFillTheWholeBar() {
        Recorder r = new Recorder();
        StackedBar.draw(r, 0, 0, 100, 6, TRACK, List.of(new StackedBar.Segment(30, A)));
        assertEquals(100, segmentsOf(r).get(0).w());
    }

    @Test
    void nullSegmentsAreSkippedNotCrashed() {
        Recorder r = new Recorder();
        List<StackedBar.Segment> segs = new ArrayList<>();
        segs.add(null);
        segs.add(new StackedBar.Segment(10, A));
        StackedBar.draw(r, 0, 0, 100, 8, TRACK, segs);
        assertEquals(1, segmentsOf(r).size());
    }
}
