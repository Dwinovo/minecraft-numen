package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.HashMap;
import java.util.Map;

/**
 * 滚动容器的记账:内容比视口高时整体上下位移,画一根拇指,收滚轮。
 *
 * <p><b>布局账只算一次。</b>宿主照常把每一行摆在它本来的位置上,{@link #measure} 把这些
 * 位置记成基线;之后滚动只是按基线减去偏移量重新落位,不重算布局、不重建控件(重建会把
 * 输入框里没保存的草稿一起丢掉)。
 *
 * <p>宿主分两层:会滚的行放在传进来的这个 {@link UiRoot} 里,不滚的(抬头、收尾按钮、
 * 结果胶囊)放另一个 root,由宿主在裁剪区外自己画。下拉弹层也在裁剪区外画——它本来就该
 * 越出视口。
 */
public final class ScrollBox {

    /** 一格滚轮走多少像素。 */
    private static final int STEP = 14;

    private final Map<Widget, Integer> baseYs = new HashMap<>();
    private int x, y, w, viewH, contentH, scrollY;

    /**
     * build 收尾时调一次:框住视口、记下每一行的基线。
     *
     * @param contentH 内容总高(最后一行的底边减去视口顶边)
     */
    public void measure(UiRoot ui, int x, int y, int w, int viewH, int contentH) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.viewH = viewH;
        this.contentH = contentH;
        this.scrollY = Math.min(scrollY, max());
        baseYs.clear();
        for (Widget rw : ui.widgetsView()) {
            baseYs.put(rw, rw.y());
        }
        reposition();
    }

    /** 回到顶部(换一份内容时用:上一份滚到哪儿了与这份无关)。 */
    public void toTop() {
        scrollY = 0;
    }

    /** 还能往下滚多少;0 = 内容装得下,不画拇指也不收滚轮。 */
    public int max() {
        return Math.max(0, contentH - viewH);
    }

    /** 当前偏移量——宿主手绘的东西也在这个区里的话,得自己减掉它。 */
    public int offset() {
        return scrollY;
    }

    /** 这个纵坐标落在视口里吗(视口外的行虽被裁掉,坐标上仍在,点击要按可视区裁决)。 */
    public boolean inside(double my) {
        return my >= y && my < y + viewH;
    }

    /** 进裁剪区:这两句之间画的东西都会被视口裁掉（行控件、宿主手绘的几条）。 */
    public void beginClip(IDrawSurface s) {
        s.pushScissor(x, y, w + NumenStyle.SCROLLBAR_W, viewH);
    }

    public void endClip(IDrawSurface s) {
        s.popScissor();
    }

    /** 拇指:内容装不下时提示"下面还有"。在裁剪区外画。 */
    public void renderThumb(IDrawSurface s, NumenTheme.Colors c) {
        if (max() <= 0) {
            return;
        }
        int thumbH = Math.max(10, viewH * viewH / contentH);
        int thumbY = y + (viewH - thumbH) * scrollY / max();
        s.fillRect(x + w, thumbY, NumenStyle.SCROLLBAR_W, thumbH, c.divider());
    }

    /** @return true = 这一格滚轮归本容器(落在视口里且滚得动)。 */
    public boolean scrolled(double my, double delta) {
        if (max() <= 0 || !inside(my)) {
            return false;
        }
        scrollY = Math.max(0, Math.min(max(), scrollY - (int) (delta * STEP)));
        reposition();
        return true;
    }

    /** 滚动 = 全部行控件按基线整体位移。 */
    private void reposition() {
        for (Map.Entry<Widget, Integer> e : baseYs.entrySet()) {
            Widget rw = e.getKey();
            rw.setBounds(rw.x(), e.getValue() - scrollY, rw.w(), rw.h());
        }
    }
}
