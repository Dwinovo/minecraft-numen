package com.dwinovo.numen.core.pathing.execute;

/**
 * 视角步进量化器:视角变化按鼠标像素量化逼近目标,不瞬间对准。
 *
 * <p>一次步进的数学:角度差 → 按灵敏度折算成整数像素(四舍五入)→
 * 再折回角度加到当前视角上。大角度差一 tick 内即可基本转到位,但落点
 * 永远只能是像素栅格上的角度——残差在半像素以内时像素数取整为 0,
 * 视角就停在那里。这决定了"是否已对准"必须用实际视角做射线判定,
 * 而不能拿角度相等做判定。
 *
 * <p>pitch 未指定(目标 pitch 与当前相等,即"只关心 yaw")时,向
 * [-20°, 10°] 的常态区间每 tick 回正 1°。
 *
 * <p>不复刻视角随机抖动(那是客户端反作弊用途,服务端 NPC 无此需求)。
 *
 * <p>纯数学类,不依赖 MC,可独立单测。
 */
public final class AimProcessor {

    /** 常态俯仰区间下界(抬头不超过 20°)。 */
    public static final float PITCH_LEVEL_MIN = -20.0f;
    /** 常态俯仰区间上界(低头不超过 10°)。 */
    public static final float PITCH_LEVEL_MAX = 10.0f;

    /** 鼠标灵敏度(0~1),决定像素栅格粒度;0.5 时一像素 = 0.15°。 */
    private final double sensitivity;

    public AimProcessor() {
        this(0.5);
    }

    public AimProcessor(double sensitivity) {
        this.sensitivity = sensitivity;
    }

    /** 一次步进的结果视角。 */
    public record Rotation(float yaw, float pitch) {}

    /**
     * 从当前视角向目标视角步进一 tick。yaw 差不归一(直接用原始差值
     * 累进,视角字段允许累积出界,只在角度比较处走短弧);pitch 步进后
     * 钳制到 [-90, 90]。目标 pitch 与当前相等视为"未指定",走回正逻辑。
     */
    public Rotation step(float currentYaw, float currentPitch, float desiredYaw, float desiredPitch) {
        if (desiredPitch == currentPitch) {
            desiredPitch = nudgeToLevel(desiredPitch);
        }
        float yaw = currentYaw + stepAngle(desiredYaw - currentYaw);
        float pitch = clampPitch(currentPitch + stepAngle(desiredPitch - currentPitch));
        return new Rotation(yaw, pitch);
    }

    /** 一 tick 内可落地的角度变化:量化到整数像素再折回角度。 */
    public float stepAngle(float angleDelta) {
        return mouseToAngle(angleToMouse(angleDelta));
    }

    /** 角度差折算成整数鼠标像素(四舍五入)。 */
    public double angleToMouse(float angleDelta) {
        float minAngleChange = mouseToAngle(1);
        return Math.round(angleDelta / minAngleChange);
    }

    /**
     * 像素数折回角度。中间量刻意保持 double 与 float 的混合精度:
     * 一次 double 缩放、一次 float 缩放,与真实鼠标输入的取整路径一致。
     */
    public float mouseToAngle(double mouseDelta) {
        double f = sensitivity * (double) 0.6f + (double) 0.2f;
        return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f;
    }

    /** 未指定 pitch 时向常态区间每 tick 回正 1°。 */
    public static float nudgeToLevel(float pitch) {
        if (pitch < PITCH_LEVEL_MIN) {
            return pitch + 1;
        } else if (pitch > PITCH_LEVEL_MAX) {
            return pitch - 1;
        }
        return pitch;
    }

    /** 角度差归一到 (-180, 180],保证转短弧。 */
    public static float normalizeDelta(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped <= -180.0f) {
            wrapped += 360.0f;
        }
        if (wrapped > 180.0f) {
            wrapped -= 360.0f;
        }
        return wrapped;
    }

    /** pitch 钳制到物理可达的 [-90, 90]。 */
    public static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    /**
     * 输入向量帧变换:移动方向按"目标 yaw"定义,而实体物理按"实际
     * (已步进)yaw"解释输入字段。把以目标 yaw 为前方的输入 (xxa, zza)
     * 折算到实际 yaw 帧上,两帧产生同一个世界方向——视角未步进到位时
     * 移动仍按目标朝向。
     *
     * @param xxa 目标帧横移冲量(左正右负)
     * @param zza 目标帧前后冲量(前正后负)
     * @param targetYawDeg 定义移动方向的目标 yaw(度)
     * @param actualYawDeg 实体当前已应用的 yaw(度)
     * @return {@code [xxa', zza']},写入实体输入字段的值
     */
    public static float[] remapInput(float xxa, float zza, float targetYawDeg, float actualYawDeg) {
        double d = Math.toRadians(targetYawDeg - actualYawDeg);
        float cos = (float) Math.cos(d);
        float sin = (float) Math.sin(d);
        return new float[]{xxa * cos - zza * sin, zza * cos + xxa * sin};
    }
}
