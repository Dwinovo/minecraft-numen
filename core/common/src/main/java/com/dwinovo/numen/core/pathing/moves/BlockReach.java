package com.dwinovo.numen.core.pathing.moves;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/**
 * 身体站在某一格上时够得着哪些方块:原版的方块交互距离({@link Player#blockInteractionRange},生存 4.5、
 * 创造 5,属性或模组改了就跟着变)加上站立眼高。判据是原版 {@link Player#canInteractWithBlock} 的几何——
 * 眼睛到那一格外框的距离小于交互距离——只是眼睛取<b>站在这一格上</b>的位置:格心,脚底加站立眼高。
 *
 * <p>导航判"停在这一格算不算到位"、任务判"站在这儿能不能动手",问的都是它,所以两边永远是同一个说法:
 * 导航说到了,动手那一侧就一定认。按格心量、不按身体此刻的实际位置量,为的就是这一点。身体在格里偏开的
 * 那一截(水平最多半格见方的对角,踩在台阶上低半格)不到一格,服务端收下挖掘时本来就多给一格
 * (见 {@code BlockDigger} 的射线长度)。
 *
 * <p>纯几何、不读世界,寻路线程上的站位判定可以直接用。
 */
public record BlockReach(double eyeHeight, double range) {

    /** 这具身体此刻的交互距离与站立眼高。 */
    public static BlockReach of(Player body) {
        return new BlockReach(body.getEyeHeight(Pose.STANDING), body.blockInteractionRange());
    }

    /** 站在 {@code feet} 上,{@code pos} 在不在交互距离内。 */
    public boolean from(BlockPos feet, BlockPos pos) {
        double dx = axis(feet.getX() + 0.5, pos.getX());
        double dy = axis(feet.getY() + eyeHeight, pos.getY());
        double dz = axis(feet.getZ() + 0.5, pos.getZ());
        return dx * dx + dy * dy + dz * dz < range * range;
    }

    /**
     * 站在 {@code from} 上还差多远才够得着 {@code pos}:眼睛到它外框的距离超出交互距离的那一截,沿眼睛到
     * 外框的方向拆成水平与竖直两段。够得着时两段都是 0。
     *
     * @param horizontal 水平要挪的格数
     * @param vertical   竖直要挪的格数,正为往上
     */
    public record Gap(double horizontal, double vertical) {}

    /** 见 {@link Gap}。 */
    public Gap gap(BlockPos from, BlockPos pos) {
        double dx = axis(from.getX() + 0.5, pos.getX());
        double dz = axis(from.getZ() + 0.5, pos.getZ());
        double eyeY = from.getY() + eyeHeight;
        double up = eyeY < pos.getY() ? pos.getY() - eyeY : eyeY > pos.getY() + 1 ? pos.getY() + 1 - eyeY : 0;
        double h = Math.sqrt(dx * dx + dz * dz);
        double d = Math.sqrt(h * h + up * up);
        if (d < range) {
            return new Gap(0, 0);
        }
        double share = (d - range) / d;
        return new Gap(h * share, up * share);
    }

    /** 一根轴上,点 {@code e} 到区间 {@code [lo, lo + 1]} 的距离。 */
    private static double axis(double e, int lo) {
        return e < lo ? lo - e : e > lo + 1 ? e - (lo + 1) : 0;
    }
}
