package com.dwinovo.numen.api.client.ui;

/**
 * NumenUI 用到的键码常量——数值即 GLFW 键码(稳定跨版本),适配层原样透传,只有小键盘回车
 * 先过 {@link #canonical} 并成回车;组件库因此不需要 import 任何 MC/GLFW 类。
 */
public final class KeyCodes {

    private KeyCodes() {}

    public static final int ENTER = 257;
    /** 小键盘回车:组件里不出现,屏幕交键之前由 {@link #canonical} 并成 {@link #ENTER}。 */
    public static final int KP_ENTER = 335;
    public static final int TAB = 258;
    public static final int BACKSPACE = 259;
    public static final int DELETE = 261;
    public static final int RIGHT = 262;
    public static final int LEFT = 263;
    public static final int DOWN = 264;
    public static final int UP = 265;
    public static final int HOME = 268;
    public static final int END = 269;
    public static final int ESCAPE = 256;
    public static final int KEY_A = 65;
    public static final int KEY_C = 67;
    public static final int KEY_F = 70;
    public static final int KEY_V = 86;
    public static final int KEY_X = 88;

    /** GLFW 修饰键位掩码。 */
    public static final int MOD_SHIFT = 0x1;
    public static final int MOD_CTRL = 0x2;
    public static final int MOD_ALT = 0x4;

    /**
     * 屏幕把键交给 NumenUI 之前过这一道:小键盘回车和主键盘回车是同一个键(原版聊天框两颗都认),
     * 并成 {@link #ENTER},组件里只认这一个。
     */
    public static int canonical(int keyCode) {
        return keyCode == KP_ENTER ? ENTER : keyCode;
    }

    public static boolean ctrl(int modifiers) {
        return (modifiers & MOD_CTRL) != 0;
    }

    public static boolean shift(int modifiers) {
        return (modifiers & MOD_SHIFT) != 0;
    }

    public static boolean alt(int modifiers) {
        return (modifiers & MOD_ALT) != 0;
    }
}
