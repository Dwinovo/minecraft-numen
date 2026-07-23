package com.dwinovo.numen.core.pathing.moves;

import java.util.EnumMap;
import java.util.Map;

/**
 * 移动原语每 tick 的输出:当前状态 + 期望视角(可空)+ 本 tick 要按住的键。
 * 输入表在每次 {@link Movement#update()} 应用后清空,按键不跨 tick 粘滞。
 */
public class MovementState {

    private MovementStatus status;
    private MovementTarget target = new MovementTarget();
    private final Map<Input, Boolean> inputStates = new EnumMap<>(Input.class);

    public MovementState setStatus(MovementStatus status) {
        this.status = status;
        return this;
    }

    public MovementStatus getStatus() {
        return status;
    }

    public MovementTarget getTarget() {
        return target;
    }

    public MovementState setTarget(MovementTarget target) {
        this.target = target;
        return this;
    }

    public MovementState setInput(Input input, boolean forced) {
        inputStates.put(input, forced);
        return this;
    }

    public Map<Input, Boolean> getInputStates() {
        return inputStates;
    }

    /**
     * 期望视角:yaw/pitch 目标转角与 force 标志。
     * force=true 表示挖掘/放置需要真实对准;false 表示仅行走朝向,
     * 执行层可静默处理(只影响移动方向,不必真的转头)。
     */
    public static final class MovementTarget {

        private final boolean hasRotation;
        private final float yaw;
        private final float pitch;
        private final boolean forceRotations;

        /** 无视角要求。 */
        public MovementTarget() {
            this.hasRotation = false;
            this.yaw = 0;
            this.pitch = 0;
            this.forceRotations = false;
        }

        public MovementTarget(float yaw, float pitch, boolean forceRotations) {
            this.hasRotation = true;
            this.yaw = yaw;
            this.pitch = pitch;
            this.forceRotations = forceRotations;
        }

        public boolean hasRotation() {
            return hasRotation;
        }

        public float getYaw() {
            return yaw;
        }

        public float getPitch() {
            return pitch;
        }

        public boolean hasToForceRotations() {
            return forceRotations;
        }
    }
}
