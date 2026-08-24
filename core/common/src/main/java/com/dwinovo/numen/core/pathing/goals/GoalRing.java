package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;

/**
 * 环形站位:站到离中心 {@code [inner, outer]} 之间。
 *
 * <h2>它真正解决的是估价,不是到达判定</h2>
 * 到达判定用两个球一减就能凑出来。真正难的是<b>估价</b>:走位有两个方向 —— 太远要往里,
 * 太近要往外 —— 而"到中心的距离"只表达得了前一半。
 *
 * <p>拿它给近战用没事,因为带比她通常所在的位置更近,往里走估价变小,估价与目标同向。
 * 换成弓就正好反过来:带在 8~12 格,而她被怪贴到 0.7 格,往外走每一步估价都在变大 ——
 * A* 先往目标那边挖、烧完预算,{@code bestSoFar} 挑的是估价最小的节点,<b>也就是离怪
 * 最近的那个</b>。实测她"寻路去贴着爬行者"不是比喻,是搜索的最优解。
 *
 * <p>这里的估价是<b>到带的距离</b>:带里为零,两侧各自朝带递减。两个方向都有指路的梯度。
 *
 * <h2>竖直不计</h2>
 * 站位是水平的事:头顶三格不算"退开了",脚下三格也不算"贴上了"。
 */
public class GoalRing implements Goal {

    public final int x;
    public final int z;
    public final double inner;
    public final double outer;

    /**
     * @param inner 离中心不得近于此;{@code inner >= outer} 时退化成实心球(内沿归零)——
     *              比如大史莱姆够得比玩家还远,那时"无伤"这条带本来就不存在
     */
    public GoalRing(BlockPos centre, double inner, double outer) {
        this.x = centre.getX();
        this.z = centre.getZ();
        this.outer = outer;
        this.inner = inner >= outer ? 0.0 : inner;
    }

    private double distanceTo(int px, int pz) {
        double dx = px + 0.5 - (x + 0.5);
        double dz = pz + 0.5 - (z + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public boolean isInGoal(int px, int py, int pz) {
        double d = distanceTo(px, pz);
        return d >= inner && d <= outer;
    }

    /**
     * 到<b>带</b>的距离,不是到中心的距离。带里为零;比内沿近就往外算,比外沿远就往里算 ——
     * 两侧都朝带递减,和到达条件同向。
     */
    @Override
    public double heuristic(int px, int py, int pz) {
        double d = distanceTo(px, pz);
        double gap = d < inner ? inner - d : d > outer ? d - outer : 0.0;
        return gap * NavSettings.get().costHeuristic;
    }

    @Override
    public String toString() {
        return String.format("GoalRing{x=%d,z=%d,band=[%.2f,%.2f]}", x, z, inner, outer);
    }
}
