package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.List;
import java.util.function.Supplier;

/**
 * 堆叠条:一条底槽上按比例并排几段。用来一眼看出<b>构成</b>——命中缓存占多少、
 * 新处理占多少;数字并排列出来要一个个去比,一条条看一眼就够。
 *
 * <p>单段填充也走它(只给一段即可),那是水位条。
 *
 * <p>取数走 {@link Supplier}:这类数据(用量、水位)随时在变,build 时捕获会把过期的
 * 比例钉死在屏幕上。
 */
public final class StackedBar extends Widget {

    /** 一段:量与颜色。量为 0 的段不占位。 */
    public record Segment(long value, int argb) {}

    /**
     * 非零段的最小宽度。按比例算下来不足一像素时补到这个数——把"有一点"画成"没有"
     * 是画面在说谎。只补到一像素而不是更宽:比例才是这条条要表达的东西,为了让细段
     * 好看而挤走别的段,等于用另一种方式说谎。
     */
    public static final int MIN_VISIBLE_PX = 1;

    private final Supplier<List<Segment>> segments;

    public StackedBar(Supplier<List<Segment>> segments) {
        this.segments = segments;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (!visible) return;
        draw(s, x, y, w, h, c.inputBg(), segments.get());
    }

    /**
     * 直接画一条,不经过控件——给那些手持画布、没有 {@code UiRoot} 的地方
     * (物品页的记忆水位条)。控件的 {@link #render} 也走这里:<b>一份实现,两个入口</b>。
     *
     * <p>宽度分配对着<b>累计量</b>算,不是各段各自四舍五入后再相加:后者的误差会累积,
     * 几段之后条尾就差出好几像素,或者整条溢出。
     */
    public static void draw(IDrawSurface s, int x, int y, int w, int h,
                            int trackArgb, List<Segment> segments) {
        long total = 0;
        if (segments != null) {
            for (Segment seg : segments) {
                if (seg != null && seg.value() > 0) total += seg.value();
            }
        }
        draw(s, x, y, w, h, trackArgb, total, segments);
    }

    /**
     * 分母显式给出的版本——<b>各段之和不等于整体</b>时用它:水位条只有"已用"一段,
     * 分母是容量;拿各段之和当分母的话,唯一那段永远画满整条。
     */
    public static void draw(IDrawSurface s, int x, int y, int w, int h,
                            int trackArgb, long total, List<Segment> segments) {
        if (w <= 0 || h <= 0) return;
        s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, trackArgb);
        if (segments == null || segments.isEmpty() || total <= 0) return;

        long acc = 0;
        int drawnTo = 0;
        for (Segment seg : segments) {
            if (seg == null || seg.value() <= 0) continue;
            acc += seg.value();
            int end = (int) (w * acc / total);
            int segW = end - drawnTo;
            if (segW < MIN_VISIBLE_PX) {
                segW = Math.min(MIN_VISIBLE_PX, w - drawnTo);
            }
            if (segW <= 0) break;   // 条已经画满,后面的段没地方了
            s.fillRoundRect(x + drawnTo, y, segW, h, NumenStyle.RADIUS_CONTROL, seg.argb());
            drawnTo += segW;
        }
    }
}
