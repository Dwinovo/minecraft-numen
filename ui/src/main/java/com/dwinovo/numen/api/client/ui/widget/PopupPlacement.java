package com.dwinovo.numen.api.client.ui.widget;

/**
 * 下拉弹层摆在哪:纯算术,不碰画布,{@link Dropdown} 每次要摆或判命中都问它。
 *
 * <p>规则:
 * <ul>
 *   <li>默认贴着字段下沿往下开;下面放不下全部、而上面更宽敞,就贴着字段上沿往上开;
 *       两头都放不下取宽敞的一头,只露装得下的整行,其余在里面滚;</li>
 *   <li>高度不超过所取一侧的可用空间(按整行取,不露半行;一侧连一行都放不下时才露不整的一截);</li>
 *   <li>宽度不超过可见区域,左缘被推回区域里。</li>
 * </ul>
 * 可见区域是宿主交给 {@link UiRoot} 的 {@link UiRoot.Viewport}(窗口、面板);滚动容器里字段的坐标已经是
 * 滚动后的真实位置,所以不必另算。
 *
 * @param x  弹层左缘
 * @param y  弹层顶边
 * @param w  弹层宽
 * @param h  弹层高
 * @param up 是否往上开
 */
public record PopupPlacement(int x, int y, int w, int h, boolean up) {

    /**
     * @param anchorY 字段顶边
     * @param anchorH 字段高
     * @param wantX   期望的左缘
     * @param wantW   期望的宽
     * @param wantH   装下全部行要的高(已按行数上限封顶)
     * @param rowH    一行的高
     */
    public static PopupPlacement place(int anchorY, int anchorH, int wantX, int wantW, int wantH, int rowH,
                                       UiRoot.Viewport view) {
        long below = (long) view.bottom() - (anchorY + anchorH);
        long above = (long) anchorY - view.top();
        boolean up = wantH > below && above > below;
        long room = Math.max(0, up ? above : below);
        int h = (int) Math.min(wantH, room);
        if (room >= rowH) h -= h % rowH;   // 整行取,滚动时每一行都完整

        long maxW = (long) view.right() - view.left();
        int w = (int) Math.min(wantW, maxW);
        long x = Math.min(wantX, (long) view.right() - w);
        x = Math.max(x, view.left());
        return new PopupPlacement((int) x, up ? anchorY - h : anchorY + anchorH, w, h, up);
    }
}
