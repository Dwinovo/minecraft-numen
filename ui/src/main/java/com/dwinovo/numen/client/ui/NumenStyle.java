package com.dwinovo.numen.client.ui;

/**
 * NumenUI 几何与效果令牌——设计令牌的另一半:色彩住 {@link NumenTheme},
 * 圆角/边距/控件高/行距/悬停效果住这里。改样式细节只动这一个文件。
 *
 * <h2>收录纪律</h2>
 * 只收"跨控件复用且承载设计意图"的值;单个组件自己的布局参数(列宽、
 * 特定偏移)留在组件里——全塞进来就成了另一种散落。
 *
 * <h2>提示该落在哪:三问定去处</h2>
 * <ol>
 *   <li><b>用户此刻的注意力在哪?</b> 事情发生在他正看着的面板里 → 页内胶囊
 *       (就地反馈,不转移焦点);发生在世界里、他可能没开面板 → HUD toast。
 *       toast 是最强的打断,用得越省越值钱。</li>
 *   <li><b>需不需要留痕?</b> 看一眼就过去 → 胶囊/toast 自动消失;事后要查
 *       (出了什么错、为什么降级)→ 聊天框警示行——它是唯一同时满足"HUD 上
 *       就能看见"和"有历史可翻"的通道。</li>
 *   <li><b>要不要阻断?</b> 需要当场决定 → 模态确认卡;只是告知 → 绝不阻断。</li>
 * </ol>
 * 由此得五种去处:字段内联红字(这个框填错了)、页内胶囊(面板内操作的结果)、
 * 聊天框警示行(面板发起、世界里生效、值得留痕)、HUD toast(纯世界事件)、
 * 模态确认卡(危险且不可逆)。
 *
 * <p>红黄之分看<b>操作有没有发生</b>,不看事情严不严重:操作被挡住(名字
 * 不合规/库为空/重名)是 error;操作成功但有降级(皮肤没借到/声线失效)是
 * warning;没有降级的预期路径(非正版名本就没皮肤可借)保持静默。
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
    /** 输入类控件卡壳(STT 字段定标:比按钮更圆润的 5px)。 */
    public static final int RADIUS_FIELD = 5;

    // ---- 尺寸与间距 ----
    /** 标准控件高(输入框/下拉——STT 字段同高)。 */
    public static final int CONTROL_H = 18;
    /** 表单行距(标签+控件一组的纵向步进)。 */
    public static final int ROW_PITCH = 23;
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

    // ---- 机器行(工具调用 / 思考过程)----
    /**
     * 过程不是对话:工具调用与思考过程要一眼能和"她说的话"分开,否则读者会
     * 把机器旁白当成模型的输出。视觉语言取"引用块"那一套——左缘一条竖线 +
     * 极淡底,不用气泡的实底与圆角。
     */
    public static final int TRACE_BAR_W = 2;
    /** 竖线到内容的呼吸;内容整体比气泡再缩进一点,层级更靠后。 */
    public static final int TRACE_INDENT = 7;
    /** 机器行的圆角:比控件更方,越方越"不像话泡"。 */
    public static final int TRACE_RADIUS = 1;

    /** 输入类控件(输入框/下拉收起态)的统一卡壳:圆角描边 + 内衬底。
     *  全部输入控件走同一形制,聚焦/错误只换描边色——框样式的单一真源。 */
    public static void fieldCard(IDrawSurface s, int x, int y, int w, int h, int fill, int border) {
        s.fillRoundRect(x, y, w, h, RADIUS_FIELD, border);
        s.fillRoundRect(x + 1, y + 1, w - 2, h - 2, RADIUS_FIELD - 1, fill);
    }

    // ---- 动效 ----
    /**
     * 动效政策(全库统一):指针反馈(悬停/按下)用 {@link #HOVER_MS} 短过渡;
     * 状态转换(开关滑块/胶囊与 toast 出入场)150~250ms 缓动。新组件照单执行,
     * 不许瞬时硬切也不许自创时长。
     */
    public static final int HOVER_MS = 100;

    /** 按帧推进悬停进度(线性,恰好 HOVER_MS 走满):返回夹紧后的新进度。 */
    public static float hoverStep(float current, boolean hovered, long dtMs) {
        float step = dtMs / (float) HOVER_MS;
        float next = current + (hovered ? step : -step);
        return Math.max(0f, Math.min(1f, next));
    }

    /** ARGB 逐通道线性混合(悬停过渡的取色器)。 */
    public static int mixColor(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24)
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    // ---- 效果 ----
    /** 悬停提亮:各通道向 255 走的百分比(15% 在深蓝 accent 上几乎看不出——真机教训)。 */
    private static final int HOVER_BRIGHTEN_PCT = 22;

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
