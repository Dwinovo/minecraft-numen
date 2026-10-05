package com.dwinovo.numen.pathing.plan;

import java.util.Arrays;

import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Recall;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 身体这一步碰到的格,以及它们能不能碰:每种走法把身体经过的列与脚高范围报进来,这里数出新进入的格(起步时已经占着的
 * 不算),再统一按路线规格查——排除的语义种类、按位置的"穿过"与"站上"禁令、按种类的"不站"禁令,以及从高处落上去会
 * 踩坏的格。走法各自只管几何,碰到什么一律在这里判,只此一处。
 *
 * <p>身体碰到的是它的碰撞盒占到的格,外加脚踩的那一格(岩浆块踩上去就烫)。细雪托得住的身体站在细雪上不会陷进去,
 * 细雪对它不算危险。进入的格与脚下那一格水平方向上紧挨着的伤身的格(岩浆、火、仙人掌……)也在这里数出来,挨着它们走要加价
 * ({@link CostModel#overhead}),路线因此离它们远一点。
 *
 * <p>一步碰到的格只有十来个,记在一个按需加长的数组里、逐个比对去重;种类一律按位掩码判({@link Semantics#mask}),
 * 前提函数每一步都要问它,不建集合。
 */
final class Contact {

    /** 碰了伤身的种类:挨着它走,身子歪一点就碰上。 */
    private static final int HARMFUL = Semantics.bit(Kind.LAVA) | Semantics.bit(Kind.HAZARD);
    /** 水平方向紧挨着的四格(北东南西),{x, z} 偏移。 */
    private static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private final BodyStats body;
    private final int fromX;
    private final int fromZ;
    private final int fromLow;
    private final int fromHigh;
    /** 新进入的格,按进入的先后。 */
    private final Cells cells = new Cells();
    private int exposure;

    /** 身体起步时脚在 {@code (from 那一列, fromFeet)}:它那时占着的格不算新进入的。 */
    Contact(BodyStats body, BlockPos from, double fromFeet) {
        this.body = body;
        this.fromX = from.getX();
        this.fromZ = from.getZ();
        this.fromLow = Footing.cellOf(fromFeet);
        this.fromHigh = Clearance.topCell(body, Pose.STANDING, fromFeet);
    }

    /** 身体在 {@code (x, z)} 这一列经过,脚在 {@code lowFeet} 到 {@code highFeet} 之间。 */
    Contact column(int x, int z, double lowFeet, double highFeet) {
        int low = Footing.cellOf(Math.min(lowFeet, highFeet));
        int high = Clearance.topCell(body, Pose.STANDING, Math.max(lowFeet, highFeet));
        for (int y = low; y <= high; y++) {
            if (x == fromX && z == fromZ && y >= fromLow && y <= fromHigh) {
                continue;
            }
            cells.add(BlockPos.asLong(x, y, z));
        }
        return this;
    }

    long[] cells() {
        return Arrays.copyOf(cells.items, cells.size);
    }

    /** 这一步新进入的格与脚下那一格水平方向上紧挨着几格伤身的({@link #admit} 时数出来)。 */
    int exposure() {
        return exposure;
    }

    /** 这一格碰了伤身,一次搜索里按格记住({@link Recall})。 */
    private static final Recall.Fact<Boolean> HARM = new Recall.Fact<>(Contact::measureHarm);

    private boolean harmful(Draft draft, BlockPos pos) {
        return HARM.at(draft, body, pos.getX(), pos.getY(), pos.getZ());
    }

    /** 这一格碰了伤身:岩浆、危险方块;托得住身体的细雪不算。 */
    private static Boolean measureHarm(BlockGetter level, BodyStats body, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return (Semantics.mask(level, pos) & HARMFUL) != 0
                && !(body.walksOnPowderSnow() && level.getBlockState(pos).is(Blocks.POWDER_SNOW));
    }

    /**
     * 这些格与脚踩的 {@code support} 这条路线碰不碰得:不行就在草稿上记下哪一格、为什么。
     *
     * @param support    落定后脚踩的那一格;不是站着为 null
     * @param fromHeight 是从高处落上去的(落差超过半格):耕地、海龟蛋会被踩坏
     */
    boolean admit(Draft draft, CostModel model, BlockPos support, boolean fromHeight) {
        RouteSpec spec = model.spec();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 挨着的伤身格多半一个都没有:碰上了才建表
        Cells near = null;
        for (int i = 0; i < cells.size; i++) {
            long cell = cells.items[i];
            pos.set(cell);
            if (model.excludes(Semantics.mask(draft, pos))) {
                return draft.fail(pos, Reason.EXCLUDED);
            }
            if (model.forbids(Use.PASS, cell)) {
                return draft.fail(pos, Reason.FORBIDDEN);
            }
            for (int[] side : SIDES) {
                long beside = BlockPos.offset(cell, side[0], 0, side[1]);
                if (!cells.contains(beside) && harmful(draft, pos.set(beside))) {
                    near = Cells.add(near, beside);
                }
            }
        }
        if (support != null) {
            // 脚下那一格旁边的也算:站在岩浆坑边上,脚底一滑就下去了
            long under = support.asLong();
            for (int[] side : SIDES) {
                long beside = BlockPos.offset(under, side[0], 0, side[1]);
                if (harmful(draft, pos.set(beside))) {
                    near = Cells.add(near, beside);
                }
            }
        }
        exposure = near == null ? 0 : near.size;
        if (support == null) {
            return true;
        }
        BlockState state = draft.getBlockState(support);
        int kinds = Semantics.mask(draft, support);
        if (body.walksOnPowderSnow() && state.is(Blocks.POWDER_SNOW)) {
            kinds &= ~Semantics.bit(Kind.HAZARD);
        }
        if (model.excludes(kinds)) {
            return draft.fail(support, Reason.EXCLUDED);
        }
        if (fromHeight && (kinds & Semantics.bit(Kind.FRAGILE)) != 0) {
            return draft.fail(support, Reason.TRAMPLES);
        }
        if (model.forbids(Use.STAND, support.asLong()) || spec.bans().standingOn().contains(state.getBlock())) {
            return draft.fail(support, Reason.FORBIDDEN);
        }
        return true;
    }

    /** 一步里数出来的格,{@link BlockPos#asLong} 编码,不重复:只有十来个,逐个比对比建哈希表快。 */
    private static final class Cells {
        private long[] items = new long[8];
        private int size;

        boolean contains(long cell) {
            for (int i = 0; i < size; i++) {
                if (items[i] == cell) {
                    return true;
                }
            }
            return false;
        }

        void add(long cell) {
            if (contains(cell)) {
                return;
            }
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
            }
            items[size++] = cell;
        }

        /** 加进 {@code into};它还没建就先建一个。 */
        static Cells add(Cells into, long cell) {
            Cells out = into == null ? new Cells() : into;
            out.add(cell);
            return out;
        }
    }
}
