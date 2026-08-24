package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

/**
 * NumenUI 控件基类。坐标一律是 GUI 缩放后的绝对坐标(与画布同一空间);
 * 事件返回 true 表示已消费。焦点由 {@link UiRoot} 独家管理——控件自己
 * 只声明 {@link #focusable()} 与响应 {@link #onFocusLost()}。
 */
public abstract class Widget {

    protected int x, y, w, h;
    protected boolean visible = true;
    protected boolean enabled = true;
    /** 由 UiRoot 写入;控件读 {@link #isFocused()}。 */
    boolean focused;
    /** 由 UiRoot 在 add 时注入(剪贴板等宿主能力经由 root)。 */
    protected UiRoot root;

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public boolean contains(double mx, double my) {
        return visible && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public boolean focusable() {
        return false;
    }

    public final boolean isFocused() {
        return focused;
    }

    public void onFocusLost() {}

    public abstract void render(IDrawSurface s, NumenTheme.Colors c,
                                int mouseX, int mouseY, long nowMs);

    public boolean mouseClicked(double mx, double my, int button) { return false; }

    public boolean mouseReleased(double mx, double my, int button) { return false; }

    public boolean mouseDragged(double mx, double my, double dx, double dy) { return false; }

    public boolean mouseScrolled(double mx, double my, double delta) { return false; }

    public boolean keyPressed(int keyCode, int modifiers) { return false; }

    public boolean charTyped(char ch) { return false; }

    // ---- 只读几何 ----
    public int x() { return x; }
    public int y() { return y; }
    public int w() { return w; }
    public int h() { return h; }
    public boolean visible() { return visible; }
    public void setVisible(boolean v) { visible = v; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean e) { enabled = e; }
}
