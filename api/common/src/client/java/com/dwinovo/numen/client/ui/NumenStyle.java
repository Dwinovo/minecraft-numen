package com.dwinovo.numen.client.ui;

/**
 * NumenUI 几何与效果令牌——设计令牌的另一半:色彩住 {@link NumenTheme},
 * 圆角/边距/控件高/行距/悬停效果住这里。改样式细节只动这一个文件。
 *
 * <h2>收录纪律</h2>
 * 只收"跨控件复用且承载设计意图"的值;单个组件自己的布局参数(列宽、
 * 特定偏移)留在组件里——全塞进来就成了另一种散落。
 */
public final class NumenStyle {

    private NumenStyle() {}

    // ---- 圆角 ----
    /** 面板/卡片级容器。 */
    public static final int RADIUS_PANEL = 4;
    /** 控件级(按钮/下拉/弹层)。 */
    public static final int RADIUS_CONTROL = 3;
    /** 徽章/滚动拇指等微件。 */
    public static final int RADIUS_SMALL = 2;

    // ---- 尺寸与间距 ----
    /** 标准控件高(输入框/下拉/按钮的紧凑档)。 */
    public static final int CONTROL_H = 13;
    /** 表单行距(标签+控件一组的纵向步进)。 */
    public static final int ROW_PITCH = 18;
    /** 标签到其控件的间距。 */
    public static final int LABEL_PITCH = 10;
    /** 容器内边距。 */
    public static final int PAD = 6;
    /** 输入框文字内缩。 */
    public static final int FIELD_PAD = 4;
    /** 列表/下拉行的文字纵向内缩。 */
    public static final int ROW_TEXT_PAD = 4;
    /** 滚动拇指宽。 */
    public static final int SCROLLBAR_W = 2;
    /** 下拉弹层行数上限(视口再小也另有保底)。 */
    public static final int POPUP_MAX_ROWS = 8;

    /** 输入类控件(输入框/下拉收起态)的统一卡壳:圆角描边 + 内衬底。
     *  全部输入控件走同一形制,聚焦/错误只换描边色——框样式的单一真源。 */
    public static void fieldCard(IDrawSurface s, int x, int y, int w, int h, int fill, int border) {
        s.fillRoundRect(x, y, w, h, RADIUS_CONTROL, border);
        s.fillRoundRect(x + 1, y + 1, w - 2, h - 2, RADIUS_CONTROL - 1, fill);
    }

    // ---- 效果 ----
    /** 悬停提亮:各通道向 255 走的百分比。 */
    private static final int HOVER_BRIGHTEN_PCT = 15;

    /** 实色控件(accent/danger 底)的统一悬停提亮。 */
    public static int hoverBrighten(int argb) {
        int a = argb & 0xFF000000;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (255 - r) * HOVER_BRIGHTEN_PCT / 100;
        g += (255 - g) * HOVER_BRIGHTEN_PCT / 100;
        b += (255 - b) * HOVER_BRIGHTEN_PCT / 100;
        return a | (r << 16) | (g << 8) | b;
    }
}
