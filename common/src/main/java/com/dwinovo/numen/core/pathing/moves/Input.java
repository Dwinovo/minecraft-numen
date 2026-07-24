package com.dwinovo.numen.core.pathing.moves;

/**
 * 移动原语可请求的输入按键。执行层把这些抽象按键映射到实体的
 * 输入字段(zza/xxa/jump/sneak/sprint)与左右键动作。
 */
public enum Input {
    MOVE_FORWARD,
    MOVE_BACK,
    MOVE_LEFT,
    MOVE_RIGHT,
    JUMP,
    SNEAK,
    SPRINT,
    CLICK_LEFT,
    CLICK_RIGHT
}
