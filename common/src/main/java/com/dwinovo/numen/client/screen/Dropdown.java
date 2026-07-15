package com.dwinovo.numen.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A generic screen-driven dropdown (collapsed box → list on click), the data-agnostic sibling of
 * {@link ProviderDropdown}. Not a self-contained widget — the host renders it LAST (open list on top)
 * and routes clicks FIRST. Used for the model picker in the Settings tab.
 */
public final class Dropdown {

    public record Item(String id, String label) {}

    private static final int ROW = 16;
    private static final net.minecraft.resources.ResourceLocation FRAME =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, "button");

    private int x, y, w, h = 18;
    /** 展开列表不得越过的下边界(面板底);默认不限。越界时列表向上翻。 */
    private int dropBottom = Integer.MAX_VALUE;
    private boolean open;
    private List<Item> items;
    private String selectedId;

    public Dropdown(List<Item> items, String selectedId) {
        this.items = items;
        this.selectedId = selectedId;
    }

    public void setBounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    /** 限定展开列表的下边界(通常 = 面板底):放不下就向上翻,列表不再戳出面板。 */
    public void setDropBottom(int bottom) { this.dropBottom = bottom; }

    /** 展开列表的外框顶 y:默认贴着收起框下沿;越过 {@link #dropBottom} 则翻到上方。 */
    private int listTop() {
        int below = y + h - 2;
        int lh = items.size() * ROW + 4;
        if (below + lh <= dropBottom || y - lh + 2 < 0) return below;
        return y - lh + 2;
    }
    public String selectedId() { return selectedId; }
    public boolean isOpen() { return open; }
    public void close() { open = false; }

    private String labelOf(String id) {
        for (Item it : items) if (it.id().equals(id)) return it.label();
        return id == null ? "" : id;
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        UiTheme th = UiTheme.current();
        g.blitSprite(FRAME, x, y, w, h);
        int ty = y + (h - 8) / 2;
        Nb.text(g, font, labelOf(selectedId), x + 6, ty, th.text());
        Nb.text(g, font, open ? "▴" : "▾", x + w - 12, ty, th.textDim());

        if (open) {
            int oy = listTop();
            g.blitSprite(FRAME, x, oy, w, items.size() * ROW + 4);
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                int ry = oy + 2 + i * ROW;
                if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW) {
                    g.fill(x + 2, ry, x + w - 2, ry + ROW, 0x33000000);
                }
                Nb.text(g, font, it.label(), x + 6, ry + (ROW - 8) / 2,
                        it.id().equals(selectedId) ? th.cta() : th.text());
            }
        }
    }

    /** Returns true if consumed. The host should check {@link #selectedId()} afterwards for a change. */
    public boolean mouseClicked(double mx, double my) {
        if (open) {
            // 行命中区与 render 用同一个 listTop()+2 起点(旧实现相差 2px,还不会向上翻)。
            int oy = listTop() + 2;
            for (int i = 0; i < items.size(); i++) {
                int ry = oy + i * ROW;
                if (mx >= x && mx < x + w && my >= ry && my < ry + ROW) {
                    selectedId = items.get(i).id();
                    open = false;
                    return true;
                }
            }
            open = false;
            return true;
        }
        if (mx >= x && mx < x + w && my >= y && my < y + h) { open = true; return true; }
        return false;
    }
}
