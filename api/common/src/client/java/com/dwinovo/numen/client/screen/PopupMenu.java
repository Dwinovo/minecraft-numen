package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.mc.Sprites;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 弹出菜单(Telegram 抬头 ⋮、左上 ☰ 点开的那种):一列"图标 + 字",危险的那项标红放最下面,前面一道分隔线。
 * 走 {@link UiRoot} 的浮层通道——开着时点外面、按 Esc 都是收起,背后什么都不接。
 *
 * <p>动效照 Telegram 的 PanelAnimation:200ms 里从锚住的那个角长开,宽从一半、高从三成长满,
 * 同时淡入;收起是 150ms 淡出。收起后浮层通道已经放手,淡出那几帧由宿主调 {@link #renderFading} 画。
 */
final class PopupMenu implements UiRoot.Overlay {

    /** 一项;{@link #SEPARATOR} 是分隔线。{@code danger} = 红字红图标(删、遣散这类回不去的)。 */
    record Item(ResourceLocation icon, String label, boolean danger, Runnable action) {}

    static final Item SEPARATOR = new Item(null, null, false, null);

    private static final int SHOW_MS = 200;
    private static final int HIDE_MS = 150;
    private static final int ROW_H = 16;
    private static final int SEP_H = 5;
    private static final int PAD_V = 3;
    private static final int PAD_L = 7;
    private static final int PAD_R = 12;
    private static final int ICON_GAP = 6;
    private static final int MIN_W = 96;

    private final Font font;
    private UiRoot root;
    private List<Item> items = List.of();
    private int anchor, top, w, h;
    /** true = 挂在锚点往右下长(☰ 在左上角);false = 往左下长(⋮ 在右上角)。 */
    private boolean growRight;
    private boolean open;
    private long openedAt, closedAt;

    PopupMenu(Font font) {
        this.font = font;
    }

    /** 挂在 {@code (anchor, top)}:{@code growRight} 往右下长开,否则往左下。 */
    void open(UiRoot root, List<Item> items, int anchor, int top, boolean growRight) {
        this.root = root;
        this.items = items;
        this.anchor = anchor;
        this.top = top;
        this.growRight = growRight;
        int labelW = 0;
        int hh = PAD_V * 2;
        for (Item it : items) {
            if (it == SEPARATOR) {
                hh += SEP_H;
            } else {
                labelW = Math.max(labelW, font.width(it.label()));
                hh += ROW_H;
            }
        }
        w = Math.max(MIN_W, PAD_L + Sprites.SIZE + ICON_GAP + labelW + PAD_R);
        h = hh;
        open = true;
        openedAt = System.currentTimeMillis();
        root.openOverlay(this);
    }

    boolean isOpen() {
        return open;
    }

    /** 刚收起、还在淡出:浮层通道不再画它,宿主这几帧接着画。 */
    void renderFading(GuiGraphics g, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        if (open || now - closedAt >= HIDE_MS) return;
        draw(g, mouseX, mouseY, now, false);
    }

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        draw(((McDrawSurface) s).graphics(), mouseX, mouseY, System.currentTimeMillis(), true);
    }

    private void draw(GuiGraphics g, int mouseX, int mouseY, long now, boolean live) {
        float alpha;
        int cw = w, ch = h;
        if (live) {
            float p = Math.min(1f, (now - openedAt) / (float) SHOW_MS);
            cw = Math.round(w * (0.5f + 0.5f * Anim.easeOutCubic(Math.min(1f, p / 0.6f))));
            ch = Math.round(h * (0.3f + 0.7f * Anim.easeOutCubic(Math.min(1f, p / 0.9f))));
            alpha = 0.2f + 0.8f * Math.min(1f, p / 0.3f);
        } else {
            alpha = 1f - (now - closedAt) / (float) HIDE_MS;
        }
        UiTheme t = UiTheme.current();
        int x = growRight ? anchor : anchor - cw;
        g.setColor(1f, 1f, 1f, Math.max(0.05f, alpha));
        NumenStyle.box(new McDrawSurface(g, font), x, top, cw, ch, t.aiFill(), t.aiBorder());
        g.enableScissor(x + 1, top + 1, x + cw - 1, top + ch - 1);
        int left = left();
        int right = left + w;
        int y = top + PAD_V;
        for (Item it : items) {
            if (it == SEPARATOR) {
                g.fill(left + 1, y + SEP_H / 2, right - 1, y + SEP_H / 2 + 1, t.surfaceBorder());
                y += SEP_H;
                continue;
            }
            boolean hot = live && alpha >= 1f && mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW_H;
            if (hot) g.fill(left + 1, y, right - 1, y + ROW_H, t.chipFill());
            int ink = it.danger() ? t.fail() : t.text();
            Sprites.draw(g, it.icon(), left + PAD_L, y + (ROW_H - Sprites.SIZE) / 2, Sprites.SIZE,
                    it.danger() ? t.fail() : t.textDim());
            Nb.text(g, font, it.label(), left + PAD_L + Sprites.SIZE + ICON_GAP, y + (ROW_H - font.lineHeight) / 2 + 1, ink);
            y += ROW_H;
        }
        g.disableScissor();
        g.setColor(1f, 1f, 1f, 1f);
    }

    /** 长满时的左缘。 */
    private int left() {
        return growRight ? anchor : anchor - w;
    }

    @Override
    public boolean overlayClicked(double mx, double my, int button) {
        if (mx < left() || mx >= left() + w || my < top || my >= top + h) return false;   // 外面:浮层通道收起
        int y = top + PAD_V;
        for (Item it : items) {
            int ih = it == SEPARATOR ? SEP_H : ROW_H;
            if (it != SEPARATOR && my >= y && my < y + ih) {
                // 先收起再做:这一项可能开确认卡,确认卡也走同一条浮层通道
                root.closeOverlay(this);
                closeOverlay();
                it.action().run();
                return true;
            }
            y += ih;
        }
        return true;   // 菜单里的空处、分隔线:吞掉,不收
    }

    @Override
    public void closeOverlay() {
        if (!open) return;
        open = false;
        closedAt = System.currentTimeMillis();
    }
}
