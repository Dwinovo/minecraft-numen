package com.dwinovo.numen.api.client.ui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.api.client.ui.widget.WidgetTestSupport.C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可展开的一截与它的开合钮:露出多高照 {@link Reveal#slide()} 走(tdesktop SlideWrap 的 150ms、线性),露不出来的点不到;
 * 开合钮收着时写里面各项的值,展开后三角朝下;改过的选择行行尾有 ×,点它改回去。
 */
class SlideWrapTest {

    private static final int ROW = 20;

    @Test
    void theSlideRevealIsLinearOverOneHundredFiftyMs() {
        Reveal r = Reveal.slide();
        r.setOpen(true);
        r.step(1000);
        r.step(1000 + Reveal.SLIDE_MS / 2);
        assertEquals(0.5f, r.value(), 1e-3, "线性:走了一半时间就露出一半");
        r.step(1000 + Reveal.SLIDE_MS);
        assertEquals(1f, r.value());
    }

    private static SlideWrap wrapWithTwoRows(UiRoot root, Reveal reveal, AtomicInteger picked) {
        SlideWrap wrap = root.add(new SlideWrap(reveal));
        wrap.setBounds(0, 100, 200, ROW * 2);
        Dropdown first = wrap.add(new Dropdown(List.of("a", "b"), 0, picked::set).row("声线", 16));
        first.setBounds(0, 100, 200, ROW);
        Dropdown second = wrap.add(new Dropdown(List.of("x", "y"), 0, i -> {}).row("模式", 16));
        second.setBounds(0, 100 + ROW, 200, ROW);
        return wrap;
    }

    @Test
    void collapsedItShowsNothingAndTakesNoClicks() {
        UiRoot root = new UiRoot();
        SlideWrap wrap = wrapWithTwoRows(root, Reveal.slide(), new AtomicInteger(-1));
        assertEquals(0, wrap.shownH());
        assertFalse(root.mouseClicked(50, 105, 0), "收着的行点不到");
        assertFalse(root.hasOverlay());
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        wrap.render(s, C, 50, 105, 0);
        assertTrue(s.texts.isEmpty(), "收着什么都不画");
    }

    @Test
    void halfwayOnlyTheShownPartIsLiveAndItIsClipped() {
        UiRoot root = new UiRoot();
        Reveal reveal = Reveal.slide();
        reveal.setOpen(true);
        reveal.step(0);
        reveal.step(Reveal.SLIDE_MS / 2);
        SlideWrap wrap = wrapWithTwoRows(root, reveal, new AtomicInteger(-1));
        assertEquals(ROW, wrap.shownH(), "露出一半 = 第一行");
        assertTrue(wrap.contains(50, 100 + ROW - 1));
        assertFalse(wrap.contains(50, 100 + ROW), "第二行还在裁掉的那一截里");
        assertNull(wrap.childAt(50, 100 + ROW + 5));
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        wrap.render(s, C, 0, 0, 0);
        assertEquals(1, s.maxScissorDepth, "里面的行画在裁剪区里");
        assertEquals(0, s.scissorDepth);
    }

    @Test
    void openRowsWorkLikeAnyOtherAndOpenTheirPopupThroughTheRoot() {
        UiRoot root = new UiRoot();
        Reveal reveal = Reveal.slide();
        reveal.snap(true);
        AtomicInteger picked = new AtomicInteger(-1);
        wrapWithTwoRows(root, reveal, picked);
        assertTrue(root.mouseClicked(50, 105, 0));
        assertTrue(root.hasOverlay(), "里面的下拉经同一条浮层通道打开");
    }

    @Test
    void movingTheWrapMovesWhatIsInsideIt() {
        UiRoot root = new UiRoot();
        Reveal reveal = Reveal.slide();
        reveal.snap(true);
        SlideWrap wrap = wrapWithTwoRows(root, reveal, new AtomicInteger(-1));
        Widget second = wrap.children().get(1);
        wrap.setBounds(0, 90, 200, ROW * 2);
        assertEquals(90 + ROW, second.y());
        assertSame(second, wrap.childAt(50, 90 + ROW + 1));
    }

    @Test
    void itHoldsNoFocusableControls() {
        UiRoot root = new UiRoot();
        SlideWrap wrap = root.add(new SlideWrap(Reveal.slide()));
        wrap.setBounds(0, 0, 100, 20);
        assertThrows(IllegalArgumentException.class, () -> wrap.add(new TextField("", v -> {})));
    }

    @Test
    void theExpandRowShowsTheSummaryWhileClosedAndFlipsTheReveal() {
        Reveal reveal = Reveal.slide();
        ExpandRow row = new ExpandRow("更多选项", () -> "声线:无 · 模式:生存", 16, reveal);
        row.setBounds(0, 0, 300, ROW);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        row.render(s, C, -1, -1, 0);
        assertTrue(s.texts.contains("更多选项"));
        assertTrue(s.texts.contains("声线:无 · 模式:生存"), "收着时写出里面各项的值");

        assertTrue(row.mouseClicked(10, 10, 0));
        assertTrue(reveal.open());
        reveal.snap(true);
        s.reset();
        row.render(s, C, -1, -1, 0);
        assertFalse(s.texts.contains("声线:无 · 模式:生存"), "展开后那几行自己摆着,不再写摘要");
    }

    @Test
    void aChangedRowShowsACrossThatRevertsIt() {
        AtomicBoolean changed = new AtomicBoolean(false);
        AtomicReference<String> reverted = new AtomicReference<>();
        UiRoot root = new UiRoot();
        Dropdown d = root.add(new Dropdown(List.of("默认(无)", "Alex"), 0, i -> changed.set(i != 0))
                .row("声线", 16).revertible(changed::get, () -> {
                    reverted.set("yes");
                    changed.set(false);
                }));
        d.setBounds(0, 0, 200, ROW);
        int crossX = 200 - 16 - 4;

        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        d.render(s, C, -1, -1, 0);
        assertFalse(s.texts.contains("×"), "没改过不出 ×");
        assertFalse(d.overRevert(crossX, 10));

        changed.set(true);
        s.reset();
        d.render(s, C, -1, -1, 0);
        assertTrue(s.texts.contains("×"), "改过了行尾出 ×");
        assertTrue(d.overRevert(crossX, 10));
        assertTrue(root.mouseClicked(crossX, 10, 0));
        assertEquals("yes", reverted.get());
        assertFalse(root.hasOverlay(), "点 × 是改回去,不是展开");

        d.setEnabled(false);
        changed.set(true);
        assertFalse(d.overRevert(crossX, 10), "置灰的行不出 ×");
    }
}
