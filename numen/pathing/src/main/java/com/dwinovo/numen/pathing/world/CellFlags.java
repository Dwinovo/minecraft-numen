package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 每种方块状态一个预先算好的标志字,给搜索的快判断读:一格是不是"什么都不用想"的格,身体怎么待在里面或脚怎么踩上去,
 * 读一个字就知道。标志字全部由精确的几何与语义推出({@link Boxes}、{@link Semantics}),不另立一套判据;
 * 推不出"什么都不用想"的状态,一律不带 {@link #PASS}、{@link #WHOLE},搜索碰到这样的格就走精确判断。
 *
 * <ul>
 *   <li>{@link #PASS}:碰撞箱不随世界或身体变、没有碰撞,没有任何语义种类,不含流体,不改步速与起跳:身体穿得过去,
 *       穿过去也不会对它起任何反应(空气、高草、花、火把……);</li>
 *   <li>{@link #WHOLE}:碰撞箱不随世界或身体变、正好是整格,除"会塌"外没有语义种类,不含流体,不改步速与起跳:
 *       身体哪一部分都进不去,脚踩上去高度正好是它的顶(石头、泥土、树叶、沙子……);</li>
 *   <li>{@link #FALLING}:会塌({@link Kind#FALLING}),只与 {@link #WHOLE} 同时出现;</li>
 *   <li>{@link #WET}:含着水({@link Semantics#wet});</li>
 *   <li>{@link #CLIMBISH}:可能是可攀爬的格({@link Semantics#mayClimb});</li>
 *   <li>{@link #FLUID}:含着流体(水、岩浆、含水的方块):脚所在的格有流体,站在里面跳不起来;</li>
 *   <li>{@link #HARMFUL}:碰了伤身(岩浆、火、仙人掌……,{@link Kind#LAVA}、{@link Kind#HAZARD}),不论碰撞箱怎么样;</li>
 *   <li>{@link #UNLOADED}:这一格所在的区块不在快照里,表里没有这一位,只由读世界的一方给出。</li>
 * </ul>
 * 其余(半砖、楼梯、栅栏、门、水、梯子、危险与机关……)既不是 PASS 也不是 WHOLE。
 */
public final class CellFlags {

    public static final int PASS = 1;
    public static final int WHOLE = 2;
    public static final int FALLING = 4;
    public static final int UNLOADED = 8;
    public static final int WET = 16;
    public static final int CLIMBISH = 32;
    public static final int HARMFUL = 64;
    public static final int FLUID = 128;

    /** 读到快照之外或建筑高度之外的整段空气层时,读出来的标志。 */
    public static final int AIR = PASS;

    private static final int FALLING_KIND = Semantics.bit(Kind.FALLING);
    private static final int HARMFUL_KINDS = Semantics.bit(Kind.LAVA) | Semantics.bit(Kind.HAZARD);

    private static final StateTable<Integer> FLAGS = new StateTable<>(CellFlags::compute);

    private CellFlags() {}

    /** 这个状态的标志字。 */
    public static int of(BlockState state) {
        return state.isAir() ? AIR : FLAGS.of(state);
    }

    private static int compute(BlockState state) {
        int flags = 0;
        if (Semantics.wet(state)) {
            flags |= WET;
        }
        if (Semantics.mayClimb(state)) {
            flags |= CLIMBISH;
        }
        int kinds = Semantics.stateMask(state);
        if ((kinds & HARMFUL_KINDS) != 0) {
            flags |= HARMFUL;
        }
        boolean fluid = !state.getFluidState().isEmpty();
        if (fluid) {
            flags |= FLUID;
        }
        boolean plain = !fluid && state.getBlock().getSpeedFactor() == 1.0F && state.getBlock().getJumpFactor() == 1.0F;
        if (plain && kinds == 0 && Boxes.empty(state)) {
            flags |= PASS;
        } else if (plain && (kinds & ~FALLING_KIND) == 0 && Boxes.whole(state)) {
            flags |= WHOLE | ((kinds & FALLING_KIND) != 0 ? FALLING : 0);
        }
        return flags;
    }
}
