package com.dwinovo.numen.core.nav;

import java.util.Set;

import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 她身边的地形:寻路模块第 0 层的几何与语义,按她的身体、在活世界上回答——只读已经加载的区块,没加载的列读作虚空,
 * 不触发加载或生成。任务与感知要问"这一格待不待得住、能不能站着、身体放不放得下、是什么种类、托着她的是哪几格、
 * 从这一列迈不迈得进那一列",都问这里,与寻路规划、执行判一步用的是同一份几何,不另判。
 *
 * <p>只在世界所在的线程上用;每次要问时取一份,不留着过刻。
 */
public final class Terrain {

    private final LiveWorld world;
    private final BodyStats body;

    private Terrain(LiveWorld world, BodyStats body) {
        this.world = world;
        this.body = body;
    }

    /** {@code player} 此刻所在的世界,按她的身体尺寸与能力。 */
    public static Terrain of(ServerPlayer player) {
        return new Terrain(new LiveWorld(player.serverLevel()), Snapshots.stats(player));
    }

    /** {@code (x, z)} 这一列所在的区块已经加载。 */
    public boolean loaded(int x, int z) {
        return world.isLoaded(x, z);
    }

    /** 这一格的方块;没加载是虚空空气。 */
    public BlockState state(BlockPos pos) {
        return world.getBlockState(pos);
    }

    /** 这一格是不是 {@code kind} 这一种。 */
    public boolean is(BlockPos pos, Semantics.Kind kind) {
        return Semantics.is(world, pos, kind);
    }

    /** 这一格是 {@code kinds} 里的任何一种。 */
    public boolean isAny(BlockPos pos, Set<Semantics.Kind> kinds) {
        return Semantics.isAny(world, pos, kinds);
    }

    /** 她的身体在节点 {@code cell} 上待得住(站着、攀着或浮着)。 */
    public boolean standable(BlockPos cell) {
        return Stance.at(world, body, cell) != null;
    }

    /** 落到 {@code cell} 那一列里她待得住的节点({@link Stance#settle});那一列都待不住就是它自己。 */
    public BlockPos settle(BlockPos cell) {
        return Stance.settle(world, body, cell);
    }

    /**
     * 按 {@code spec} 走的路线会不会让她在节点 {@code pos} 上站着:身体站得住、是站着({@link Stance}),脚所在、头所在、脚下那一格
     * 都不是这份规格排除的种类。找一处站着干活的地方(钓鱼)、给主人画周围哪儿能站(环视)问的都是它。
     */
    public boolean standingSpot(BlockPos pos, RouteSpec spec) {
        Stance stance = Stance.at(world, body, pos);
        if (stance == null || !stance.grounded()) {
            return false;
        }
        return !Semantics.isAny(world, pos, spec.excluded()) && !Semantics.isAny(world, pos.above(), spec.excluded())
                && !Semantics.isAny(world, stance.support(pos.getX(), pos.getZ()), spec.excluded());
    }

    /** 她站直的身体放得进脚在 {@code (x, feetY, z)} 这一格的那个位置。 */
    public boolean fits(int x, int feetY, int z) {
        return Clearance.fits(world, body, Pose.STANDING, x, feetY, z);
    }

    /** 身体盒子是 {@code box} 时,此刻托着她的方块。 */
    public Set<BlockPos> supports(AABB box) {
        return Footing.supports(world, body, box);
    }

    /**
     * 从脚在 {@code feetY} 的 {@code (fromX, fromZ)} 这一列贴地走进相邻的 {@code (x, z)} 那一列:平走、上一级、下一级三选一,按这个
     * 先后。落脚处她待得住而且是站着,从这一列迈进那一列是走过去或跳上去——与寻路判一步同一套几何;落脚、托脚、身体经过的
     * 格都不是 {@code keepOff} 里的种类。走不进去为 null;再高再深的都不算。
     *
     * @param bodyY 身体此刻脚的高度:出发那一列算不出落脚高度时(她正悬在边上)从这里起步
     */
    public Step step(int fromX, int fromZ, int feetY, double bodyY, int x, int z, Set<Semantics.Kind> keepOff) {
        double fromFeet = Footing.height(world, body, fromX, feetY, fromZ);
        if (Double.isNaN(fromFeet)) {
            fromFeet = bodyY;
        }
        for (int dy : new int[]{0, 1, -1}) {
            int y = feetY + dy;
            Stance stance = Stance.at(world, body, x, y, z);
            if (stance == null || !stance.grounded() || isAny(stance.support(x, z), keepOff)
                    || isAny(new BlockPos(x, y, z), keepOff) || isAny(new BlockPos(x, y + 1, z), keepOff)) {
                continue;
            }
            Stepping.Step step = Stepping.between(world, body, fromX, fromFeet, fromZ, Integer.signum(x - fromX),
                    Integer.signum(z - fromZ), stance.feetY());
            if (step != Stepping.Step.BLOCKED) {
                return new Step(y, step == Stepping.Step.JUMP);
            }
        }
        return null;
    }

    /**
     * 迈进相邻一列的那一步。
     *
     * @param y    落脚那一格的高度
     * @param jump 要起跳才上得去
     */
    public record Step(int y, boolean jump) {}
}
