package com.dwinovo.numen.client.ui;

/**
 * 单行文本按像素宽收口:放得下原样,放不下截断缀 "…"。
 *
 * <p>截断以<b>码点</b>为单位——{@code String.substring} 按 char 截会把增补平面字符
 * (emoji、部分汉字)劈成半个代理对,画出来是乱码方块。量宽全权交给
 * {@link IDrawSurface#textWidth},与 drawText 同一字体度量,不自设每字符宽。
 */
public final class TextClip {

    /** 单字符省略号(U+2026):原版字体自带,窄按钮也放得下。 */
    private static final String ELLIPSIS = "…";

    private TextClip() {}

    /**
     * 收口到 {@code maxWidth} 像素内。放得下原样返回;放不下截到"前缀+…"装得进为止;
     * 窄到连省略号都放不下返回空串。{@code maxWidth <= 0} 视作没有排版约束,原样返回
     * ——没量过尺寸的控件保持旧行为,不无声吞字。
     */
    public static String fit(IDrawSurface s, String text, int maxWidth) {
        String t = text == null ? "" : text;
        if (maxWidth <= 0 || s.textWidth(t) <= maxWidth) {
            return t;
        }
        int room = maxWidth - s.textWidth(ELLIPSIS);
        if (room <= 0) {
            return "";
        }
        // 按码点数二分最长可容前缀:宽度随码点数单调不减,二分成立
        int lo = 0;
        int hi = t.codePointCount(0, t.length());
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (s.textWidth(t.substring(0, t.offsetByCodePoints(0, mid))) <= room) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return t.substring(0, t.offsetByCodePoints(0, lo)) + ELLIPSIS;
    }
}
