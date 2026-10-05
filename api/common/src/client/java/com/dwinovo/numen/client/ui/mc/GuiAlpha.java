package com.dwinovo.numen.client.ui.mc;

/**
 * 界面此刻整体的不透明度:淡入淡出的卡、菜单、通知一类整块出现和消失的东西,画之前设一个值,画完设回 1。
 *
 * <p>1.21.6 起 {@code GuiGraphics} 不再有全局着色({@code setColor} 与 {@code RenderSystem.getShaderColor} 一并没了),
 * 画的东西攒成渲染状态、帧末统一画。这个值由 {@code MixinGuiGraphics} 在填充、文字、贴图提交的那一刻乘进颜色的透明度,
 * 语义与旧代的着色器颜色一致:设了之后画的每一样都跟着淡。只在客户端渲染线程上用。
 */
public final class GuiAlpha {

    private static float alpha = 1f;

    private GuiAlpha() {}

    public static void set(float value) {
        alpha = value;
    }

    public static float get() {
        return alpha;
    }

    /** 把 {@code argb} 的透明度乘上此刻的整体透明度。 */
    public static int apply(int argb) {
        if (alpha >= 1f) {
            return argb;
        }
        int a = Math.round((argb >>> 24) * Math.max(0f, alpha));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
