package com.dwinovo.numen.pathing.plan;

import java.util.EnumSet;

import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 身体这一步碰到的格,以及它们能不能碰:每种走法把身体经过的列与脚高范围报进来,这里数出新进入的格(起步时已经占着的
 * 不算),再统一按路线规格查——排除的语义种类、按位置的"穿过"与"站上"禁令、按种类的"不站"禁令,以及从高处落上去会
 * 踩坏的格。走法各自只管几何,碰到什么一律在这里判,只此一处。
 *
 * <p>身体碰到的是它的碰撞盒占到的格,外加脚踩的那一格(岩浆块踩上去就烫)。细雪托得住的身体站在细雪上不会陷进去,
 * 细雪对它不算危险。
 */
final class Contact {

    private final BodyStats body;
    private final int fromX;
    private final int fromZ;
    private final int fromLow;
    private final int fromHigh;
    private final LongArrayList cells = new LongArrayList();
    private final LongOpenHashSet seen = new LongOpenHashSet();

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
            long cell = BlockPos.asLong(x, y, z);
            if (seen.add(cell)) {
                cells.add(cell);
            }
        }
        return this;
    }

    long[] cells() {
        return cells.toLongArray();
    }

    /**
     * 这些格与脚踩的 {@code support} 这条路线碰不碰得:不行就在草稿上记下哪一格、为什么。
     *
     * @param support    落定后脚踩的那一格;不是站着为 null
     * @param fromHeight 是从高处落上去的(落差超过半格):耕地、海龟蛋会被踩坏
     */
    boolean admit(Draft draft, CostModel model, BlockPos support, boolean fromHeight) {
        RouteSpec spec = model.spec();
        for (int i = 0; i < cells.size(); i++) {
            long cell = cells.getLong(i);
            BlockPos pos = BlockPos.of(cell);
            if (spec.excludesAny(Semantics.kinds(draft, pos))) {
                return draft.fail(pos, Reason.EXCLUDED);
            }
            if (model.forbids(Use.PASS, cell)) {
                return draft.fail(pos, Reason.FORBIDDEN);
            }
        }
        if (support == null) {
            return true;
        }
        BlockState state = draft.getBlockState(support);
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        kinds.addAll(Semantics.kinds(draft, support));
        if (body.walksOnPowderSnow() && state.is(Blocks.POWDER_SNOW)) {
            kinds.remove(Kind.HAZARD);
        }
        if (spec.excludesAny(kinds)) {
            return draft.fail(support, Reason.EXCLUDED);
        }
        if (fromHeight && kinds.contains(Kind.FRAGILE)) {
            return draft.fail(support, Reason.TRAMPLES);
        }
        if (model.forbids(Use.STAND, support.asLong()) || spec.bans().standingOn().contains(state.getBlock())) {
            return draft.fail(support, Reason.FORBIDDEN);
        }
        return true;
    }
}
