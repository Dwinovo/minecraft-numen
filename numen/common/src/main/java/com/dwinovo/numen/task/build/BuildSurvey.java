package com.dwinovo.numen.task.build;

import com.dwinovo.numen.WorkProfile;
import com.dwinovo.numen.task.dig.DigTaskRecord;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一件施工的每一格此刻是什么情形:已经对上、不去动、得先挖开、缺料、放得进去。{@code numen.build.place} 只放"放得进去"的那些、
 * 受理前问还有没有,{@code numen.build.diff} 把整份照这同一个判据数给脚本——施工与查询读的是同一份。
 *
 * <p>她站在哪儿、离这一格多远不在判据里:放一格不看距离({@link BuildPlacer})。生存模式下图纸要的格里立着别的东西,得先由
 * {@code numen.work.dig} 挖开,{@code numen.build.place} 不挖;创造模式照原版一下就碎,放的时候直接顶掉。立着东西、身上却没有
 * 要放的料的格不叫她挖:没料挖了只是在主人的地上挖个坑。
 */
public final class BuildSurvey {

    /** 一格的情形。 */
    public enum State {
        /** 已经是图纸要的样子。 */
        DONE,
        /** 涉及液体:要放水或岩浆、或要把现有的液体换成空气。她不放也排不掉,不去动,回执单列 {@code liquid}({@link BuildCellRules#leavesLiquid})。 */
        LIQUID,
        /** 不去动:规则不许、玩家的东西、砸不动、出了边界。要问主人的不在这里,问了才知道。 */
        SKIPPED,
        /** 生存模式下立着别的方块,得先挖开。 */
        DIG,
        /** 生存模式下立着别的方块,身上却没有要放的料。 */
        SHORT,
        /** 放得进去:{@code numen.build.place} 这一次就放。 */
        READY
    }

    private final NumenPlayer player;
    private final BuildTaskRecord r;
    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final boolean instaBreak;

    BuildSurvey(NumenPlayer player, BuildTaskRecord r, BuildCellRules rules, BuildInventory inv) {
        this.player = player;
        this.r = r;
        this.rules = rules;
        this.inv = inv;
        this.instaBreak = WorkProfile.of(player).instaBreak();
    }

    /** 给查询用:不派活,按这一份施工图此刻的世界与背包数一遍。 */
    public static BuildSurvey of(NumenPlayer player, BuildTaskRecord r) {
        BuildCellRules rules = new BuildCellRules(player, r);
        BuildInventory inv = new BuildInventory(player);
        return new BuildSurvey(player, r, rules, inv);
    }

    /** 这一格此刻的情形。 */
    public State of(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState now = rules.peek(pos);
        if (target.matches(now)) {
            return State.DONE;
        }
        if (BuildCellRules.leavesLiquid(target, now)) {
            return State.LIQUID;
        }
        if (rules.refused(target) || rules.hopeless(target)) {
            return State.SKIPPED;
        }
        if (!instaBreak && DigTaskRecord.wants(null, now)) {
            return BuildCellRules.isAirTarget(target) || affordable(target) ? State.DIG : State.SHORT;
        }
        return State.READY;
    }

    /**
     * 她此刻付不付得起这一格:不花料的格永远付得起,否则身上够件数。施工逐格扣料前问的也是它。
     */
    boolean affordable(BuildTaskRecord.Target target) {
        int cost = r.consumeMaterials && rules.costsMaterial(target) ? target.materialCount() : 0;
        if (cost <= 0) {
            return true;
        }
        return inv.hasItems(target.item(), cost);
    }

    /** 数一遍整份:每种情形几格,以及要挖的格(离她近的在前)。 */
    public Tally tally() {
        Map<State, Integer> counts = new EnumMap<>(State.class);
        List<BlockPos> dig = new ArrayList<>();
        List<BuildTaskRecord.Target> pending = new ArrayList<>();
        int heldWater = 0;
        for (BuildTaskRecord.Target target : r.targets) {
            State state = of(target);
            counts.merge(state, 1, Integer::sum);
            if (state != State.DONE && state != State.SKIPPED && state != State.LIQUID) {
                pending.add(target);
            }
            if (target.heldWater()) {
                heldWater++;
            }
            if (state == State.DIG) {
                dig.add(target.pos());
            }
        }
        BlockPos here = player.blockPosition();
        dig.sort(Comparator.comparingDouble(p -> p.distSqr(here)));
        return new Tally(counts, dig, pending, heldWater);
    }

    /**
     * 数出来的结果。
     *
     * @param dig 要先挖开的格,离她近的在前
     * @param pending 还没了结的格({@link #left()} 数的就是它们),按施工图里的先后
     * @param heldWater 要的方块本来含着水、水被去掉了的格({@link BuildTaskRecord.Target#heldWater})
     */
    public record Tally(Map<State, Integer> counts, List<BlockPos> dig, List<BuildTaskRecord.Target> pending,
                        int heldWater) {

        public int count(State state) {
            return counts.getOrDefault(state, 0);
        }

        /** 还没了结的格:不算已经对上的,也不算不去动的。 */
        public int left() {
            return count(State.DIG) + count(State.SHORT) + count(State.READY);
        }
    }
}
