package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.KeyCodes;
import com.dwinovo.numen.client.ui.NumenStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 面板的键盘导航与几何。渲染不在这儿——那需要一块画布,而这些都是纯算术。 */
class SelectPanelTest {

    /** 一页假数据:记下回车落在哪一行,并允许行数中途变化。 */
    private static final class FakePage implements SelectPanel.Page {

        private final List<SelectPanel.Row> rows = new ArrayList<>();
        private final List<Integer> activated = new ArrayList<>();
        private boolean changes = true;

        FakePage(int n) {
            for (int i = 0; i < n; i++) {
                rows.add(new SelectPanel.Row("skill" + i, "", i % 2 == 0));
            }
        }

        @Override public String title() {
            return "技能";
        }

        @Override public List<SelectPanel.Row> rows() {
            return List.copyOf(rows);
        }

        @Override public boolean activate(int index) {
            activated.add(index);
            return changes;
        }
    }

    private static SelectPanel panel(FakePage page) {
        SelectPanel p = new SelectPanel(page);
        p.setBounds(0, 0, 200, p.preferredHeight());
        return p;
    }

    @Test
    void theFirstRowIsSelectedToBeginWith() {
        assertEquals(0, panel(new FakePage(5)).selectedIndex());
    }

    @Test
    void arrowsMoveTheSelection() {
        SelectPanel p = panel(new FakePage(5));
        p.keyPressed(KeyCodes.DOWN, 0);
        p.keyPressed(KeyCodes.DOWN, 0);
        assertEquals(2, p.selectedIndex());
        p.keyPressed(KeyCodes.UP, 0);
        assertEquals(1, p.selectedIndex());
    }

    @Test
    void selectionStopsAtBothEndsInsteadOfWrapping() {
        SelectPanel p = panel(new FakePage(3));
        p.keyPressed(KeyCodes.UP, 0);
        assertEquals(0, p.selectedIndex(), "顶上再往上不该绕到底部 —— 那会让人以为自己看漏了");
        for (int i = 0; i < 10; i++) {
            p.keyPressed(KeyCodes.DOWN, 0);
        }
        assertEquals(2, p.selectedIndex());
    }

    @Test
    void enterActivatesTheSelectedRow() {
        FakePage page = new FakePage(4);
        SelectPanel p = panel(page);
        p.keyPressed(KeyCodes.DOWN, 0);
        p.keyPressed(KeyCodes.ENTER, 0);
        assertEquals(List.of(1), page.activated);
    }

    @Test
    void otherKeysAreLeftToTheHostSoEscapeCanCloseThePanel() {
        SelectPanel p = panel(new FakePage(3));
        assertFalse(p.keyPressed(KeyCodes.ESCAPE, 0), "关面板不是面板自己的事");
        assertFalse(p.keyPressed(KeyCodes.TAB, 0));
    }

    @Test
    void anEmptyPageSwallowsEnterWithoutBlowingUp() {
        FakePage page = new FakePage(0);
        SelectPanel p = panel(page);
        p.keyPressed(KeyCodes.ENTER, 0);
        p.keyPressed(KeyCodes.DOWN, 0);
        assertTrue(page.activated.isEmpty());
        assertEquals(0, p.selectedIndex());
    }

    @Test
    void refreshKeepsTheSelectionInsideAShrunkList() {
        FakePage page = new FakePage(6);
        SelectPanel p = panel(page);
        for (int i = 0; i < 5; i++) {
            p.keyPressed(KeyCodes.DOWN, 0);
        }
        assertEquals(5, p.selectedIndex());
        page.rows.subList(2, 6).clear();
        p.refresh();
        assertEquals(1, p.selectedIndex(), "行少了,选中项得跟着回到范围内");
    }

    @Test
    void heightGrowsWithRowsUpToTheCap() {
        int few = SelectPanel.heightFor(3, true);
        int many = SelectPanel.heightFor(NumenStyle.POPUP_MAX_ROWS, true);
        assertTrue(few < many);
        assertEquals(many, SelectPanel.heightFor(NumenStyle.POPUP_MAX_ROWS + 40, true),
                "超过上限就滚动,不是无限长高");
    }

    @Test
    void aTitledPanelIsTallerThanAnUntitledOne() {
        assertTrue(SelectPanel.heightFor(4, true) > SelectPanel.heightFor(4, false));
    }
}
