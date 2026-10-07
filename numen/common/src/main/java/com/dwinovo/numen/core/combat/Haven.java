package com.dwinovo.numen.core.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 「往哪儿跑」——挑一个<b>具体的落点</b>,不是一个方向。
 *
 * <h2>为什么必须是一个点</h2>
 * "离每一只都三十二格"在十几只怪围着时<b>无解</b>,搜索只能交出 {@code bestSoFar};而逃跑
 * 势场是 {@code 1/d²},五格之后每格只改善千分之几,走一格却要 4.6 —— 排序里几乎是噪声。
 * 于是每次重规划挑的方向都不一样,她在二十来格见方的框里绕圈,实测三十几秒没跑出去。
 *
 * <p>换成一个坐标之后:目标永远可达,<b>方向只挑一次</b>,路径重算只改路线不改目标。
 * 方向的连续性就是不绕圈的全部原因。
 *
 * <h2>为什么在扇形里随机取</h2>
 * 正后方一条道走到黑的话,那个方向要是刷怪区,她就一头扎进去。原版
 * {@code DefaultRandomPos.getPosAway} 在背离威胁的 ±90° 扇形里随机取点,每段方向都不同。
 * 这里照抄那条。
 */
public final class Haven {

    /** 落点离她多远。跟 {@link Menace#FLEE_DISTANCE} 同一个数:跑到就该脱身了。 */
    private static final double REACH_OUT = Menace.FLEE_DISTANCE;

    /** 背离方向左右各这么多弧度内随机。原版 {@code getPosAway} 用的也是 ±90°。 */
    private static final double SPREAD = Math.PI / 2.0;

    /** 一个方向找不到落脚点就换一个再试。八次覆盖整个扇形还有余。 */
    private static final int TRIES = 8;

    /** 竖直方向最多接受的落差:再多说明那是个坑或者悬崖。 */
    private static final int MAX_DROP = 4;

    private Haven() {}

    /**
     * 挑一个落点。
     *
     * @param threats 这一刻的威胁;它们的<b>重心</b>决定往哪边背离
     * @return 落点;实在找不到可站的地方时返回 {@code null},调用方自行退化
     */
    public static BlockPos awayFrom(LivingEntity self, List<? extends Entity> threats) {
        if (threats.isEmpty()) {
            return null;
        }
        double cx = 0.0;
        double cz = 0.0;
        for (Entity t : threats) {
            cx += t.getX();
            cz += t.getZ();
        }
        cx /= threats.size();
        cz /= threats.size();

        double awayX = self.getX() - cx;
        double awayZ = self.getZ() - cz;
        double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (len < 1.0e-4) {
            // 正好站在重心上:没有"背离"可言,随便挑个方向。
            awayX = 1.0;
            awayZ = 0.0;
            len = 1.0;
        }
        double baseAngle = Math.atan2(awayZ / len, awayX / len);

        RandomSource random = self.getRandom();
        for (int i = 0; i < TRIES; i++) {
            double angle = baseAngle + (random.nextDouble() * 2.0 - 1.0) * SPREAD;
            int x = (int) Math.round(self.getX() + Math.cos(angle) * REACH_OUT);
            int z = (int) Math.round(self.getZ() + Math.sin(angle) * REACH_OUT);
            BlockPos landing = standableNear(self.level(), x, self.getBlockY(), z);
            if (landing != null) {
                return landing;
            }
        }
        return null;
    }

    /**
     * 在 {@code (x, z)} 这一列上、离 {@code aroundY} 最近的可站位置。
     *
     * <p>只认<b>已加载</b>的方块:未加载的区块里随手指一个点,寻路会一路挖过去或者当场失败。
     */
    private static BlockPos standableNear(Level level, int x, int aroundY, int z) {
        for (int dy = 0; dy <= MAX_DROP; dy++) {
            for (int sign : new int[] {1, -1}) {
                int y = aroundY + dy * sign;
                BlockPos feet = new BlockPos(x, y, z);
                if (!level.isLoaded(feet)) {
                    return null;   // 区块没加载,这个方向不算数
                }
                if (standable(level, feet)) {
                    return feet;
                }
                if (dy == 0) {
                    break;   // dy = 0 时正负是同一格,别试两遍
                }
            }
        }
        return null;
    }

    /** 脚下踩得实、身位两格空。 */
    private static boolean standable(Level level, BlockPos feet) {
        BlockState ground = level.getBlockState(feet.below());
        return !ground.isAir()
                && ground.getFluidState().isEmpty()
                && level.getBlockState(feet).isAir()
                && level.getBlockState(feet.above()).isAir();
    }
}
