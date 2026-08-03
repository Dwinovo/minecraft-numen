package com.dwinovo.numen.client.ui;

/**
 * NumenUI 用到的键码常量——数值即 GLFW 键码(稳定跨版本),适配层原样透传,
 * 组件库因此不需要 import 任何 MC/GLFW 类。
 */
public final class KeyCodes {

    private KeyCodes() {}

    public static final int ENTER = 257;
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
    public static final int KEY_V = 86;
    public static final int KEY_X = 88;

    /** GLFW 修饰键位掩码。 */
    public static final int MOD_CTRL = 0x2;

    public static boolean ctrl(int modifiers) {
        return (modifiers & MOD_CTRL) != 0;
    }
}
