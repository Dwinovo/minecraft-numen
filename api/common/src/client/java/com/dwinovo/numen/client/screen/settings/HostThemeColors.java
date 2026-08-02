package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.NumenTheme;

/**
 * 宿主主题 → NumenUI 令牌的桥。内嵌进 G 面板的 NumenUI 组件必须穿宿主的
 * 皮肤(纸面底、金色 CTA、主题化正文色),否则就是客厅里的异色地砖;
 * 独立新式屏幕(如 /numen 设置)才用 NumenTheme 自带配色。
 * 按主题实例记忆化——渲染帧内零分配的纪律不破。
 */
public final class HostThemeColors {

    private static UiTheme cachedTheme;
    private static NumenTheme.Colors cached;

    private HostThemeColors() {}

    public static NumenTheme.Colors current() {
        UiTheme th = UiTheme.current();
        if (th != cachedTheme || cached == null) {
            cachedTheme = th;
            cached = bridge(th);
        }
        return cached;
    }

    private static NumenTheme.Colors bridge(UiTheme th) {
        return new NumenTheme.Colors(
                th.surface(),                          // panelBg(下拉弹层底=宿主纸面)
                th.cardFill(), th.surfaceBorder(),     // sectionBg / divider
                th.text(), th.textDim(), th.faint(),   // 文字三级
                th.cta(), th.fail(), th.ok(),          // accent(金)/danger/success
                th.field(), th.border(),               // 输入底/描边
                th.chipFill(),                         // hover(半透明胶囊)
                (th.cta() & 0xFFFFFF) | 0x30000000,    // selected:金色淡染,与宿主选中语言一致
                th.field(), th.text(),                 // badge 底/字
                th.aiFill(),                           // toast info(气泡同源)
                mix(th.run(), 0xFFFFFFFF, 0.72f),      // toast warn:主题琥珀的浅调
                mix(th.fail(), 0xFFFFFFFF, 0.72f),     // toast error:主题红的浅调
                th.text());                            // toast 文字(宿主全系浅色纸面,深字可读)
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }
}
