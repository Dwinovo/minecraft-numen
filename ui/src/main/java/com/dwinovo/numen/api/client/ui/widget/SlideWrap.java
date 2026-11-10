package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * 可以收起来的一截:里面的行照全开时的样子摆好,露出多高跟着宿主手里的 {@link Reveal} 走,露不出来的被裁掉、点不到。
 * 照 tdesktop 的 {@code Ui::SlideWrap}:外框的高度在收起(0)与里面的全高之间按进度取,里面的行不动、只被外框裁;
 * 进度用 {@link Reveal#slide()}(150ms、线性)。
 *
 * <p>状态在 {@link Reveal} 里,不在控件里:宿主重建控件,开合与正在走的过渡都还在。外框高度变了,外框下面的东西怎么挪由宿主
 * 照同一个 {@link #shownH()} 定(对话框整张卡跟着长高)。
 *
 * <p>里面放不取焦点的控件(选择行、按钮):焦点只由 {@link UiRoot} 在它直接管的控件之间给。外框挪动({@link #setBounds})时
 * 里面的行一起挪。
 */
public final class SlideWrap extends Widget {

    private final Reveal reveal;
    private final List<Widget> children = new ArrayList<>();
    /** 里面全开时的高。 */
    private int fullH;
    private boolean placed;

    public SlideWrap(Reveal reveal) {
        this.reveal = reveal;
    }

    /**
     * 外框:{@code h} 是里面全开时的高。挪动外框时,已经摆进来的行跟着挪同样的距离。
     */
    @Override
    public void setBounds(int x, int y, int w, int h) {
        int dy = y - this.y;
        super.setBounds(x, y, w, h);
        fullH = h;
        if (placed && dy != 0) {
            for (Widget child : children) {
                child.setBounds(child.x, child.y + dy, child.w, child.h);
            }
        }
        placed = true;
    }

    /**
     * 往里面摆一行。坐标照全开时的样子给(绝对坐标);外框要先加进 {@link UiRoot}、定过外框,里面的行才接得上浮层通道。
     *
     * @throws IllegalArgumentException 取焦点的控件(见类说明)
     */
    public <T extends Widget> T add(T child) {
        if (child.focusable()) {
            throw new IllegalArgumentException("a SlideWrap holds controls that take no focus: " + child);
        }
        child.root = root;
        children.add(child);
        return child;
    }

    public List<Widget> children() {
        return java.util.Collections.unmodifiableList(children);
    }

    /** 此刻露出多高:0 全收,{@code h} 全开。 */
    public int shownH() {
        return Math.round(fullH * reveal.value());
    }

    @Override
    public boolean contains(double mx, double my) {
        return visible && mx >= x && mx < x + w && my >= y && my < y + shownH();
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int shown = shownH();
        if (shown <= 0) {
            return;
        }
        // 露不出来的那一截上的指针不算在里面的行上:那里此刻是外框下面的东西
        boolean in = contains(mouseX, mouseY);
        int mx = in ? mouseX : Integer.MIN_VALUE, my = in ? mouseY : Integer.MIN_VALUE;
        s.pushScissor(x, y, w, shown);
        for (Widget child : children) {
            if (child.visible) child.render(s, c, mx, my, nowMs);
        }
        s.popScissor();
    }

    /** 这一点上(露出来的部分里)是里面的哪一行;没有是 {@code null}。宿主问悬停提示时用它。 */
    public Widget childAt(double mx, double my) {
        if (!contains(mx, my)) return null;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.visible && child.contains(mx, my)) return child;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Widget child = childAt(mx, my);
        return child != null && child.enabled && child.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        Widget child = childAt(mx, my);
        return child != null && child.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        for (Widget child : children) {
            if (child.visible && child.mouseReleased(mx, my, button)) return true;
        }
        return false;
    }
}
