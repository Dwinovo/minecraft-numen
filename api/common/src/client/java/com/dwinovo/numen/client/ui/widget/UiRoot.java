package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 控件树宿主:渲染顺序、事件路由、单焦点管理、浮层(打开的下拉等)。
 * MC 屏幕类持有一个 UiRoot,把自己的 render/mouse/key 回调原样转发进来——
 * 屏幕层只做组装,交互逻辑全在这层以下,纯 JVM 可测。
 *
 * <h2>路由规则</h2>
 * <ul>
 *   <li>浮层优先:有浮层时点击/滚轮先给浮层;浮层外的点击关浮层且不下传
 *       (符合"点开下拉后点别处 = 只是关掉"的通用直觉)。</li>
 *   <li>点击:从后往前(后加的在上层)找第一个命中的控件;可聚焦者获得焦点,
 *       点空白清焦点。</li>
 *   <li>键盘/字符:只发给焦点控件。</li>
 * </ul>
 */
public final class UiRoot {

    private final List<Widget> widgets = new ArrayList<>();
    private Widget focused;
    private Overlay overlay;

    private Supplier<String> clipboardGet = () -> "";
    private Consumer<String> clipboardSet = s -> {};
    /** GUI 缩放后的视口高(屏幕层 init/resize 时设置);浮层用它避免越出屏幕底缘。 */
    private int viewportH = Integer.MAX_VALUE;

    public void setViewportHeight(int h) { this.viewportH = h; }

    public int viewportHeight() { return viewportH; }

    /** 浮层契约:打开状态的下拉弹层等,绘制在最后、事件在最先。 */
    public interface Overlay {
        void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs);

        /** @return true = 事件被浮层消费;false = 点在浮层外(root 将关闭浮层)。 */
        boolean overlayClicked(double mx, double my, int button);

        default boolean overlayScrolled(double mx, double my, double delta) { return false; }

        /** root 侧关闭通知(点浮层外/ESC)。 */
        void closeOverlay();
    }

    public <T extends Widget> T add(T widget) {
        widget.root = this;
        widgets.add(widget);
        return widget;
    }

    public void clear() {
        focused = null;
        overlay = null;
        widgets.clear();
    }

    /** 宿主注入剪贴板能力(MC 屏幕接 keyboardHandler);测试注入假实现。 */
    public void setClipboard(Supplier<String> get, Consumer<String> set) {
        this.clipboardGet = get;
        this.clipboardSet = set;
    }

    public String clipboard() { return clipboardGet.get(); }

    public void copyToClipboard(String text) { clipboardSet.accept(text); }

    public void openOverlay(Overlay o) { overlay = o; }

    public void closeOverlay(Overlay o) {
        if (overlay == o) overlay = null;
    }

    public boolean hasOverlay() { return overlay != null; }

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        for (Widget w : widgets) {
            if (w.visible) w.render(s, c, mouseX, mouseY, nowMs);
        }
        if (overlay != null) overlay.renderOverlay(s, c, mouseX, mouseY, nowMs);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (overlay != null) {
            Overlay o = overlay;
            if (o.overlayClicked(mx, my, button)) return true;
            o.closeOverlay();
            overlay = null;
            return true;   // 浮层外的点击只负责关浮层,不下传
        }
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            if (!w.visible || !w.contains(mx, my)) continue;
            boolean handled = w.enabled && w.mouseClicked(mx, my, button);
            setFocus(w.focusable() && w.enabled ? w : null);
            return handled || w.focusable();
        }
        setFocus(null);
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            if (w.visible && w.mouseReleased(mx, my, button)) return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, double dx, double dy) {
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            if (w.visible && w.mouseDragged(mx, my, dx, dy)) return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (overlay != null && overlay.overlayScrolled(mx, my, delta)) return true;
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            if (w.visible && w.contains(mx, my) && w.mouseScrolled(mx, my, delta)) return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        if (overlay != null && keyCode == com.dwinovo.numen.client.ui.KeyCodes.ESCAPE) {
            overlay.closeOverlay();
            overlay = null;
            return true;
        }
        return focused != null && focused.keyPressed(keyCode, modifiers);
    }

    public boolean charTyped(char ch) {
        return focused != null && focused.charTyped(ch);
    }

    public Widget focusedWidget() { return focused; }

    void setFocus(Widget target) {
        if (focused == target) return;
        if (focused != null) {
            focused.focused = false;
            focused.onFocusLost();
        }
        focused = target;
        if (target != null) target.focused = true;
    }
}
