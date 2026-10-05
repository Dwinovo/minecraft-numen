package com.dwinovo.numen.client.ui.mc;

/**
 * 整块画面淡入淡出用的当前不透明度。
 *
 * <p>26.1 的 GUI 是"提取"模型:没有全局着色器颜色可以乘进之后画的一切,每个绘制调用的颜色自带
 * alpha。所以淡入淡出的屏幕与卡片把不透明度记在这里({@link #set}),本层的画法
 * ({@link McDrawSurface}、{@code Nb}、{@link Sprites},以及屏幕里直接落到画布上的调用)经
 * {@link #argb} 把它乘进自己要画的颜色——和旧版"着色器颜色的 alpha 乘进后面每一笔"是同一个口径。
 *
 * <p>只在渲染线程上用;一屏画完必须 {@code set(1f)} 复位。
 */
public final class Fade {

    private static float alpha = 1f;

    private Fade() {}

    /** 之后画的一切乘上 {@code a}(0..1)。 */
    public static void set(float a) {
        alpha = a;
    }

    /** 当前的不透明度。 */
    public static float get() {
        return alpha;
    }

    /** {@code argb} 的 alpha 通道乘上当前不透明度,颜色不变;不透明度是 1 时原样返回。 */
    public static int argb(int argb) {
        if (alpha >= 1f) {
            return argb;
        }
        int a = Math.round((argb >>> 24) * Math.max(0f, alpha));
        return a << 24 | (argb & 0xFFFFFF);
    }
}
