package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.KeyCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 选择框:尺寸、过滤、键盘、手填行、关闭。 */
class PickerBoxTest {

    private static final int DIM_W = 400, DIM_H = 300;

    private static final List<PickerBox.Item> ALL = List.of(
            new PickerBox.Item("alpha", "Alpha", "alpha-host", List.of("1M", "✓"), false),
            new PickerBox.Item("beta", "Beta", "beta-host", List.of(), true),
            new PickerBox.Item("gamma", "Gamma", "gamma-host", List.of(), false));

    private final UiRoot root = new UiRoot();
    private final PickerBox box = new PickerBox();
    private final AtomicReference<PickerBox.Item> picked = new AtomicReference<>();
    private final WidgetTestSupport.FakeSurface surface = new WidgetTestSupport.FakeSurface();
    private long now;

    private PickerBox.Source bySearch() {
        return q -> {
            List<PickerBox.Item> hit = new ArrayList<>();
            for (PickerBox.Item it : ALL) {
                if (q.isEmpty() || it.title().toLowerCase().contains(q.toLowerCase())) hit.add(it);
            }
            return List.of(new PickerBox.Section("全部", hit));
        };
    }

    private PickerBox.Table table;

    private void open(PickerBox.FreeEntry free) {
        box.open(root, 0, 0, DIM_W, DIM_H,
                new PickerBox.Labels("选择", "共 3 个", "搜索", List.of("↑↓ 选择", "回车 确定", "Esc 关闭"),
                        q -> "没有找到“" + q + "”"),
                table, bySearch(), free, picked::set);
        frame();
        now += DialogBox.SHOW_MS;   // 淡入走完,字才画得出来
        frame();
    }

    private void frame() {
        surface.reset();
        root.render(surface, WidgetTestSupport.C, 0, 0, now += 16);
    }

    private void type(String text) {
        for (char ch : text.toCharArray()) root.charTyped(ch);
        frame();
    }

    @Test
    void sizeIsWithinWindowAndFollowsContent() {
        PickerBox.Size roomy = PickerBox.size(1000, 600, 100, 300);
        assertEquals(100, roomy.h(), "高随内容");
        assertEquals(300, roomy.w());
        assertEquals(280, PickerBox.size(1000, 1000, 5000, 300).h(), "内容再多也有固定上限");
        PickerBox.Size small = PickerBox.size(1000, 300, 5000, 300);
        assertEquals(300 * 86 / 100, small.h(), "小窗口:卡整块落在窗口里,不比窗口高");
        PickerBox.Size narrow = PickerBox.size(200, 600, 100, 300);
        assertEquals(200 * 88 / 100, narrow.w(), "窄窗口:宽不超过窗口");
    }

    @Test
    void drawsSectionsRowsAndTheCurrentMark() {
        open(null);
        assertTrue(surface.texts.containsAll(List.of("选择", "共 3 个", "全部", "Alpha", "alpha-host", "✓")));
        assertTrue(surface.texts.contains("↑↓ 选择"));
    }

    @Test
    void tableShowsAFixedHeaderRowAndColumnCells() {
        table = new PickerBox.Table("名称", List.of(new PickerBox.Column("上下文", 5, true),
                new PickerBox.Column("推理", 3, false)), 300);
        open(null);
        assertTrue(surface.texts.containsAll(List.of("名称", "上下文", "推理", "1M", "✓")));
    }

    @Test
    void startsOnTheCurrentItemAndEnterPicksIt() {
        open(null);
        root.keyPressed(KeyCodes.ENTER, 0);
        assertEquals("beta", picked.get().id());
        assertFalse(box.isOpen());
    }

    @Test
    void arrowsMoveAndStopAtTheEnds() {
        open(null);
        root.keyPressed(KeyCodes.DOWN, 0);
        root.keyPressed(KeyCodes.DOWN, 0);
        root.keyPressed(KeyCodes.DOWN, 0);   // 到头不绕圈
        root.keyPressed(KeyCodes.ENTER, 0);
        assertEquals("gamma", picked.get().id());
    }

    @Test
    void typingFiltersAndResetsTheSelectionToTheFirstHit() {
        open(null);
        type("gam");
        assertTrue(surface.texts.contains("Gamma") && !surface.texts.contains("Alpha"));
        root.keyPressed(KeyCodes.ENTER, 0);
        assertEquals("gamma", picked.get().id());
    }

    @Test
    void freeEntryAppearsUnlessTheQueryIsAnId() {
        PickerBox.FreeEntry free = new PickerBox.FreeEntry("手填", q -> "使用“" + q + "”", "目录里没有");
        open(free);
        assertFalse(surface.texts.contains("手填"), "没搜时不给手填行");
        type("zzz-1");
        assertTrue(surface.texts.contains("使用“zzz-1”"));
        root.keyPressed(KeyCodes.ENTER, 0);   // 没有命中项,手填行是唯一可选的
        assertEquals("zzz-1", picked.get().id());
    }

    @Test
    void queryEqualToAnIdHidesTheFreeRow() {
        PickerBox.FreeEntry free = new PickerBox.FreeEntry("手填", q -> "使用“" + q + "”", "目录里没有");
        open(free);
        type("Beta");   // 与 id beta 只差大小写
        assertFalse(surface.texts.stream().anyMatch(t -> t.startsWith("使用")));
    }

    @Test
    void noMatchSaysSoAndEnterDoesNothing() {
        open(null);
        type("qqq");
        assertTrue(surface.texts.contains("没有找到“qqq”"));
        root.keyPressed(KeyCodes.ENTER, 0);
        assertNull(picked.get());
        assertTrue(box.isOpen());
    }

    @Test
    void escAndClickOutsideClose() {
        open(null);
        root.keyPressed(KeyCodes.ESCAPE, 0);
        assertFalse(box.isOpen());
        assertFalse(root.hasOverlay());

        open(null);
        assertTrue(root.mouseClicked(2, 2, 0), "卡外点击只负责关");
        assertFalse(box.isOpen());
        assertNull(picked.get());
    }

    @Test
    void clickingARowPicksIt() {
        open(null);
        // 假画布行高 9:条目行 22、节标题 15;卡高 149 居中在 300 里,列表顶 125,Alpha 行在 140~162
        root.mouseClicked(DIM_W / 2.0, 150, 0);
        assertEquals("alpha", picked.get().id());
        assertFalse(box.isOpen());
    }

    @Test
    void modalKeepsTheWheelAndKeysFromTheBackground() {
        open(null);
        assertTrue(root.mouseScrolled(10, 10, -1));
        assertTrue(root.keyPressed(KeyCodes.TAB, 0));
    }
}
