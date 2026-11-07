package com.dwinovo.numen.api.entity;

import net.minecraft.util.Mth;

/**
 * 颈部模型:视角怎么从此刻的朝向转到要的朝向。闭环的二阶跟踪——角速度有上限、角速度的变化(角加速度)也有上限,起转与停转都是软的,
 * 离目标近了按"刚好刹得住"的速度收,所以不冲过也不拖尾;偏航与俯仰当作一个二维误差一起走,同时到位,在角度平面上是直线,
 * 不是先转完一个轴再转另一个。
 *
 * <p>纯值:{@link #toward} 交出下一刻的颈部,不碰任何身体。真身体每刻按它转({@link Look#steer}),控制器往前推演时复制同一个值逐刻往前算,
 * 所以推演里看到的朝向序列与真身体施加的完全一致——物理用的朝向与别人看到的朝向始终是同一个,没有只作用于显示的平滑,也没有随机抖动。
 *
 * @param yaw        偏航(度,累加不回绕,与原版身体的偏航同一口径)
 * @param pitch      俯仰(度,向下为正)
 * @param yawSpeed   偏航角速度(度每刻)
 * @param pitchSpeed 俯仰角速度(度每刻)
 */
public record Neck(float yaw, float pitch, float yawSpeed, float pitchSpeed) {

    /** 角速度上限(度每刻,两轴合起来的大小):每秒 320 度,真人甩鼠标的快慢。 */
    public static final double MAX_SPEED = 16.0;
    /** 角加速度上限(度每刻的平方)。 */
    public static final double MAX_ACCEL = 4.0;
    /** 刹停曲线的保守系数:离散的刻上,刚好刹得住的速度略往下收一点,免得冲过。 */
    private static final double BRAKE = 0.5;

    /** 静止在 {@code (yaw, pitch)}。 */
    public static Neck at(float yaw, float pitch) {
        return new Neck(yaw, pitch, 0, 0);
    }

    /** 往 {@code (targetYaw, targetPitch)} 转一刻之后的颈部;偏航走近的那一边(差值不超过半圈)。 */
    public Neck toward(float targetYaw, float targetPitch) {
        double errorYaw = Mth.wrapDegrees(targetYaw - yaw);
        double errorPitch = targetPitch - pitch;
        double error = Math.hypot(errorYaw, errorPitch);
        double speed = Math.hypot(yawSpeed, pitchSpeed);
        if (error <= MAX_ACCEL && speed <= MAX_ACCEL) {
            // 离目标不到一个加速度、转得也不快:这一刻的步子本来就在加速度之内,一步到位
            return new Neck((float) (yaw + errorYaw), targetPitch, 0, 0);
        }
        // 想要的角速度:朝着误差的方向,大小是"刚好在到达时刹住"的速度,不超过上限
        double want = Math.min(MAX_SPEED, BRAKE * Math.sqrt(2 * MAX_ACCEL * error));
        double wantYaw = error == 0 ? 0 : errorYaw / error * want;
        double wantPitch = error == 0 ? 0 : errorPitch / error * want;
        // 角速度这一刻最多变 MAX_ACCEL(两轴合起来的大小)
        double changeYaw = wantYaw - yawSpeed;
        double changePitch = wantPitch - pitchSpeed;
        double change = Math.hypot(changeYaw, changePitch);
        if (change > MAX_ACCEL) {
            changeYaw *= MAX_ACCEL / change;
            changePitch *= MAX_ACCEL / change;
        }
        float nextYawSpeed = (float) (yawSpeed + changeYaw);
        float nextPitchSpeed = (float) (pitchSpeed + changePitch);
        return new Neck(yaw + nextYawSpeed, Mth.clamp(pitch + nextPitchSpeed, -90.0F, 90.0F), nextYawSpeed, nextPitchSpeed);
    }
}
