package com.dwinovo.numen.pathing.plan;

/**
 * 身体此刻身上已有的移动方式的事实,由宿主在抄身体快照时一处定下({@link com.dwinovo.numen.pathing.api.Snapshots#mobility});走法集({@link Moves#forBody})、
 * 估价与执行读的都是这一份。走、游、挖、搭一直有,不在这里;这里只有要靠身上的能力与装备才有的。
 *
 * @param mayFly      许飞:创造飞行的能力({@code Abilities.mayfly}),双击跳就能起飞
 * @param flying      此刻正在飞({@code Abilities.flying})
 * @param flyingSpeed 能力里的飞行速度({@code Abilities.getFlyingSpeed},原版 0.05)
 * @param gliderLife  胸甲位的滑翔物还能再用多少点耐久;没有滑翔物或已经坏了为 0(1.21.1 是鞘翅;1.21.2 起看滑翔组件,移植时只改宿主抄这一项的判据)
 * @param fireworks   背包与副手里的烟花火箭一共几支
 * @param rocketBurn  一支烟花至少推多少刻({@code FireworkRocketEntity} 的寿命是 {@code 10 × 飞行时长} 再加最多 11 刻的随机,取保底);没有烟花为 0
 */
public record Mobility(boolean mayFly, boolean flying, float flyingSpeed, int gliderLife, int fireworks, int rocketBurn) {

    /** 原版飞行速度。 */
    public static final float VANILLA_FLYING_SPEED = 0.05F;
    /** 只会走、游、挖、搭:没有飞行能力,没有滑翔物,没有烟花。 */
    public static final Mobility WALKER = new Mobility(false, false, VANILLA_FLYING_SPEED, 0, 0, 0);

    /** 有可用的滑翔物。 */
    public boolean canGlide() {
        return gliderLife > 0;
    }
}
