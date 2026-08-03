package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.ListView;
import com.dwinovo.numen.client.ui.widget.UiRoot;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * 设置页左侧子导航——NumenUI 版的壳:分区标签一行一个,选中态胶囊底 +
 * 左缘 accent 竖条,悬停淡入(动效由 ListView 统一给)。分区列表与选中
 * 下标由宿主传入,面板只管画与命中。
 */
public final class NavPanel {

    private static final int ROW_H = 20;

    private final UiRoot ui = new UiRoot();
    private final IntConsumer onSelect;
    private List<String> labels = List.of();
    private int selected;

    public NavPanel(IntConsumer onSelect) {
        this.onSelect = onSelect;
    }

    public void build(int x, int y, int w, int h, List<String> labels, int selected) {
        this.labels = labels;
        this.selected = selected;
        ui.clear();
        ListView<String> list = ui.add(new ListView<String>(labels, ROW_H, this::renderRow, null)
                .rowClick((index, xInRow) -> {
                    onSelect.accept(index);
                    return true;
                }));
        list.setBounds(x, y, w, h);
    }

    private void renderRow(IDrawSurface s, NumenTheme.Colors c, String label, int index,
                           int rx, int ry, int rw, int rh, boolean sel, boolean hovered) {
        boolean active = index == selected;
        if (active) {
            s.fillRoundRect(rx, ry, rw, rh - 2, NumenStyle.RADIUS_CONTROL, c.selected());
            s.fillRect(rx, ry + 2, 2, rh - 6, c.accent());   // 左缘竖条:当前分区
        }
        s.drawText(label, rx + 6, ry + (rh - 2 - s.lineHeight()) / 2 + 1,
                active ? c.textPrimary() : c.textSecondary(), false);
    }

    // ---- 宿主转发面 ----

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ui.render(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }
}
