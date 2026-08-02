package com.dwinovo.numen.client.screen;

/**
 * "思考开关 × 推理强度"两个控件与单存储值(auto/off/low/medium/high)之间
 * 的映射。纯函数——UI 的取值语义在这里钉死并被测试覆盖:
 * 关闭→off;自动→auto;开启→取强度档(开关型方言强度无意义,固定 medium 档)。
 */
public final class ReasoningChoice {

    public static final int SWITCH_AUTO = 0;
    public static final int SWITCH_ON = 1;
    public static final int SWITCH_OFF = 2;

    public static final int LEVEL_LOW = 0;
    public static final int LEVEL_MEDIUM = 1;
    public static final int LEVEL_HIGH = 2;

    private ReasoningChoice() {}

    public static int switchIndex(String stored) {
        if (stored == null) return SWITCH_AUTO;
        return switch (stored) {
            case "off" -> SWITCH_OFF;
            case "low", "medium", "high" -> SWITCH_ON;
            default -> SWITCH_AUTO;
        };
    }

    public static int levelIndex(String stored) {
        if (stored == null) return LEVEL_MEDIUM;
        return switch (stored) {
            case "low" -> LEVEL_LOW;
            case "high" -> LEVEL_HIGH;
            default -> LEVEL_MEDIUM;
        };
    }

    public static String compose(int switchIdx, int levelIdx) {
        return switch (switchIdx) {
            case SWITCH_OFF -> "off";
            case SWITCH_ON -> switch (levelIdx) {
                case LEVEL_LOW -> "low";
                case LEVEL_HIGH -> "high";
                default -> "medium";
            };
            default -> "auto";
        };
    }
}
