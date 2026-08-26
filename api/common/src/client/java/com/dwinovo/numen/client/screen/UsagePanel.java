package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.provider.CacheWaste;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TokenFormat;
import com.dwinovo.numen.client.ui.widget.Button;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.StackedBar;
import com.dwinovo.numen.client.ui.widget.UiRoot;

import java.util.List;
import java.util.Locale;

/**
 * Token 账卡——{@code /usage} 打开的读数面板。
 *
 * <h2>为什么是面板不是聊天文本</h2>
 * 这份数据的重点是<b>比例</b>:命中占了多少、新处理占了多少。数字并排列出来要一个个
 * 去比,一条堆叠条一眼就够。所以卡里唯一的控件只有关闭按钮,其余全是读数——它是仪表
 * 不是表单,不需要输入,也不该有任何东西能被误点。
 *
 * <h2>只读,不缓存</h2>
 * 每帧现取({@link Host#usage()} 等)。账在她说话时随时会变,存一份下来就会显示上一秒
 * 的数字。
 */
public final class UsagePanel {

    /** 屏幕侧的面:数据源与关卡。 */
    public interface Host {
        String name();

        Usage usage();

        /** 最近一轮——命中率取它,累计会被历史稀释。 */
        Usage lastUsage();

        CacheWaste waste();

        void onClose();
    }

    /** 堆叠条的高度。够看出比例即可,再高就喧宾夺主。 */
    private static final int BAR_H = 8;

    private final Host host;
    private final UiRoot ui = new UiRoot();
    private int x, y, w;

    public UsagePanel(Host host) {
        this.host = host;
    }

    public void build(int x, int y, int w, int h, int dropBottom) {
        this.x = x;
        this.y = y;
        this.w = w;
        ui.clear();
        ui.setViewportHeight(dropBottom);

        Label title = ui.add(new Label("Token 账 · " + host.name(), Label.Role.PRIMARY));
        title.setBounds(x, y + 5, w - 20, 9);

        Button close = ui.add(new Button("✕", Button.Style.GHOST, host::onClose));
        close.setBounds(x + w - 16, y + 2, 14, 14);
    }

    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        ui.render(s, c, mouseX, mouseY, nowMs);
        Usage u = host.usage();
        int ry = y + 26;
        if (u.total() <= 0) {
            s.drawText("还没有用量记录——她一轮都还没开口。", x, ry, c.textMuted(), false);
            return;
        }

        long prompt = u.promptTokens();
        long freshIn = u.input() + u.cacheWrite();

        // 输入总量 + 堆叠条:绿是命中(省下的),淡是新处理(付过的)
        ry = row(s, c, ry, "输入", group(prompt), c.textPrimary());
        if (u.reportsCache()) {
            StackedBar.draw(s, x, ry, w, BAR_H, c.inputBg(), List.of(
                    new StackedBar.Segment(u.cacheRead(), c.success()),
                    new StackedBar.Segment(freshIn, c.textMuted())));
            ry += BAR_H + 6;
            ry = sub(s, c, ry, "命中缓存", group(u.cacheRead()),
                    TokenFormat.percent1(u.cacheHitRate()) + "%", c.success());
            ry = sub(s, c, ry, "新处理", group(freshIn), null, c.textSecondary());
            if (u.cacheWrite() > 0) {
                ry = sub(s, c, ry, "其中写入缓存", group(u.cacheWrite()), null, c.textMuted());
            }
        }
        ry += 4;
        ry = row(s, c, ry, "输出", group(u.output()), c.textPrimary());
        ry = row(s, c, ry, "合计", group(u.total()), c.textPrimary());

        double last = host.lastUsage().cacheHitRate();
        if (u.reportsCache() && last >= 0) {
            ry += 4;
            // 分档同页脚:低了说明前缀正在被打穿
            int col = last >= 0.7 ? c.success() : last >= 0.3 ? c.warning() : c.danger();
            ry = row(s, c, ry, "最近一轮命中率", TokenFormat.percent1(last) + "%", col);
        }

        CacheWaste waste = host.waste();
        if (waste.missedTokens() > 0) {
            ry += 6;
            String msg = "缓存重付 " + group(waste.missedTokens())
                    + " tokens · " + waste.missCount() + " 次";
            s.fillRoundRect(x, ry, w, 20, NumenStyle.RADIUS_CONTROL, c.toastWarnBg());
            s.drawText("⚠ " + msg, x + 6, ry + 6, c.warning(), false);
            ry += 24;
            s.drawText("前缀被动过:提示词改了、工具清单变了、或缓存过期。",
                    x + 6, ry, c.textMuted(), false);
        }
    }

    /** 一行:左标题、右数字。数字右对齐——账要竖着比才看得出量级。 */
    private int row(IDrawSurface s, NumenTheme.Colors c, int ry, String label, String value, int valueColor) {
        s.drawText(label, x, ry, c.textSecondary(), false);
        s.drawText(value, x + w - s.textWidth(value), ry, valueColor, false);
        return ry + s.lineHeight() + 3;
    }

    /** 缩进的明细行,可带一个尾注(命中率百分比)。 */
    private int sub(IDrawSurface s, NumenTheme.Colors c, int ry, String label,
                    String value, String note, int color) {
        s.drawText("  " + label, x, ry, c.textMuted(), false);
        String shown = note == null ? value : value + "  " + note;
        s.drawText(shown, x + w - s.textWidth(shown), ry, color, false);
        return ry + s.lineHeight() + 2;
    }

    /** 千分位——账本上的数要看得出量级,不缩写。 */
    private static String group(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        return ui.mouseClicked(mx, my, button);
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return false;
    }
}
