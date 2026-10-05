package com.dwinovo.numen.core.nav;

import java.util.Set;

import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Sight;

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

    /** 这一格有没有可点的轮廓(第 0 层 {@link Sight#clickable}):空气、流体没有。 */
    public boolean clickable(BlockPos pos) {
        return Sight.clickable(world, pos);
    }

    /** 此刻她站在 {@code block} 上时脚所在的节点({@link Goals#standingOn});站不上去为 null。 */
    public BlockPos standingOn(BlockPos block) {
        return Goals.standingOn(world, body, block);
    }

    /**
     * 用 {@code target} 这一格:按此刻的世界列出她的候选站位({@link Goals#use})。要先确认它可点({@link #clickable})。
     */
    public Goals.Use use(BlockPos target) {
        return Goals.use(world, body, target);
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
}
