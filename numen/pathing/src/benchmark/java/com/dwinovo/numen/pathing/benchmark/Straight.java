package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.SearchView;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 起终点之间的直线上的几何事实:出题剔除平凡题的判据与报表的标签都从这里来,与任何搜索算法无关。
 *
 * <p>直线按列走:从起点到终点水平方向每一格一列(取整),每一列在上一列的脚高上下一格内找站得住的格({@link Stance})。规则:
 * <ul>
 *   <li>这一列没有站得住的格(沿线高低差超过一格、没有地面):{@code rough};</li>
 *   <li>站得住的是泡在水里的格,或脚、头那两格里有流体:{@code water};</li>
 *   <li>斜着换列时,两条边上的邻列在脚和头的高度上有实心方块挡着(切角过不去):{@code rough}。</li>
 * </ul>
 * 没有 {@code rough} 也没有 {@code water} 的直线,是明摆着一路平走过去的:这样的题没有搜索的难度,不进题库(平凡题)。
 */
final class Straight {

    /** 直线上的事实:有水、不平稳(有高于一格的台阶、没有地面或切角过不去)。 */
    record Facts(boolean water, boolean rough) {

        /** 明摆着一路平走:没有水,也不崎岖。 */
        boolean plainWalk() {
            return !water && !rough;
        }

        List<Problem.Tag> tags(Problem.EndClass endClass) {
            List<Problem.Tag> tags = new ArrayList<>();
            if (water) {
                tags.add(Problem.Tag.WATER);
            }
            if (rough) {
                tags.add(Problem.Tag.ROUGH);
            }
            if (endClass.sealed()) {
                tags.add(Problem.Tag.DIG);
            }
            if (endClass.underground()) {
                tags.add(Problem.Tag.UNDERGROUND);
            }
            return tags;
        }
    }

    private Straight() {}

    static Facts along(SearchView world, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int n = Math.max(Math.abs(dx), Math.abs(dz));
        boolean water = false;
        boolean rough = false;
        int y = from.getY();
        int px = from.getX();
        int pz = from.getZ();
        for (int i = 1; i <= n; i++) {
            int x = from.getX() + (int) Math.round((double) dx * i / n);
            int z = from.getZ() + (int) Math.round((double) dz * i / n);
            BlockPos stand = null;
            for (int dy : new int[] {0, 1, -1}) {
                if (Stance.at(world, Spec.STATS, x, y + dy, z) != null) {
                    stand = new BlockPos(x, y + dy, z);
                    break;
                }
            }
            if (stand == null) {
                rough = true;
            } else {
                y = stand.getY();
                water |= Stance.at(world, Spec.STATS, stand).kind() != Stance.Kind.GROUND || fluid(world, stand) || fluid(world, stand.above());
            }
            if (x != px && z != pz) {
                rough |= solid(world, new BlockPos(x, y, pz)) || solid(world, new BlockPos(x, y + 1, pz)) || solid(world, new BlockPos(px, y, z))
                        || solid(world, new BlockPos(px, y + 1, z));
            }
            px = x;
            pz = z;
        }
        return new Facts(water, rough);
    }

    private static boolean fluid(SearchView world, BlockPos pos) {
        return !world.getBlockState(pos).getFluidState().isEmpty();
    }

    private static boolean solid(SearchView world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isSolid() && state.getFluidState().isEmpty();
    }
}
