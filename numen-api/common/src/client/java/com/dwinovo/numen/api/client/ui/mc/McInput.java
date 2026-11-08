package com.dwinovo.numen.api.client.ui.mc;

import com.dwinovo.numen.api.client.ui.KeyCodes;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;

/**
 * 原版输入 → NumenUI 的编号。NumenUI 的键码、修饰键与鼠标键号用 GLFW 的编号({@link KeyCodes};鼠标左 0、右 1、中 2),
 * 各版本不变;原版的输入走 SDL3:键是 SDL 扫描码,修饰键分左右两位,鼠标键左 1、中 2、右 3。屏幕把键与点击交给 NumenUI
 * 之前都过这里一道,组件里只见 NumenUI 的编号。
 */
public final class McInput {

    private McInput() {}

    /**
     * 按下的键。SDL 扫描码与 GLFW 键码都按美式键位的物理键命名,一一对得上;NumenUI 用不到的键给 GLFW 的"未知键"(-1)。
     */
    public static int key(KeyEvent event) {
        int key = event.key();
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
            return KeyCodes.KEY_A + (key - InputConstants.KEY_A);
        }
        return switch (key) {
            case InputConstants.KEY_RETURN -> KeyCodes.ENTER;
            case InputConstants.KEY_NUMPADENTER -> KeyCodes.KP_ENTER;
            case InputConstants.KEY_TAB -> KeyCodes.TAB;
            case InputConstants.KEY_BACKSPACE -> KeyCodes.BACKSPACE;
            case InputConstants.KEY_DELETE -> KeyCodes.DELETE;
            case InputConstants.KEY_RIGHT -> KeyCodes.RIGHT;
            case InputConstants.KEY_LEFT -> KeyCodes.LEFT;
            case InputConstants.KEY_DOWN -> KeyCodes.DOWN;
            case InputConstants.KEY_UP -> KeyCodes.UP;
            case InputConstants.KEY_HOME -> KeyCodes.HOME;
            case InputConstants.KEY_END -> KeyCodes.END;
            case InputConstants.KEY_ESCAPE -> KeyCodes.ESCAPE;
            default -> -1;
        };
    }

    /** 修饰键:SDL 的左右两位任一按着,就是 NumenUI 的那一位。 */
    public static int modifiers(InputWithModifiers event) {
        int mods = event.modifiers();
        int out = 0;
        if ((mods & InputConstants.MOD_SHIFT) != 0) {
            out |= KeyCodes.MOD_SHIFT;
        }
        if ((mods & InputConstants.MOD_CONTROL) != 0) {
            out |= KeyCodes.MOD_CTRL;
        }
        if ((mods & InputConstants.MOD_ALT) != 0) {
            out |= KeyCodes.MOD_ALT;
        }
        return out;
    }

    /** 鼠标键号:左 0、右 1、中 2,侧键(SDL 的 4 到 8)依次是 3 到 7。 */
    public static int button(int button) {
        return switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> 2;
            default -> button - 1;
        };
    }
}
