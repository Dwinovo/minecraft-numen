package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一件施工的每一格,对她此刻站的地方而言是什么情形:已经对上、不去动、得先挖开、还立不住、够得着就放、够不着。{@code numen.build.place} 只放
 * "够得着就放"的那些、受理前问还有没有,{@code numen.build.diff} 把整份照这同一个判据数给脚本——施工与查询读的是同一份。
 *
 * <p>"够得着"就是 {@code numen.move.to(…, {arrive = "place"})} 走到的地方({@link Destination#reach}):走到了,这一格就在这里算够得着。
 * 生存模式下图纸要的格里立着别的东西,得先由 {@code numen.work.dig} 挖开,{@code numen.build.place} 不挖;创造模式照原版一下就碎,放的时候
 * 直接顶掉。立着东西、身上却没有要放的料的格不叫她挖:没料挖了只是在主人的地上挖个坑。
 */
public final class BuildSurvey {

    /** 一格的情形。 */
    public enum State {
        /** 已经是图纸要的样子(流体目标与排水不做,也算了结)。 */
        DONE,
        /** 不去动:规则不许、玩家的东西、砸不动、出了边界。要问主人的不在这里,问了才知道。 */
        SKIPPED,
        /** 生存模式下立着别的方块,得先挖开。 */
        DIG,
        /** 生存模式下立着别的方块,身上却没有要放的料。 */
        SHORT,
        /**
         * 走原生车道(像右键那样放,{@link BuildTaskRecord.Target#itemPlace})的格此刻放下去立不住(原版 {@code canSurvive}——物品
         * 落位时问的就是它),而它相邻的设计格还有没盖好的:托着它的(下面那块、背后那面墙、上面挂着它的)还没有,等那一格好了它才是
         * {@link #REACH} 或 {@link #FAR}。够不够得着都一样:走过去也放不下。照图直写的格不问这一句,立不立得住由建完之后的落定说;
         * 相邻的都盖好了还立不住的也不在这里——图纸要的在那儿本来立不住,照常交给 {@code numen.build.place},它放不下就照实说。
         */
        UNHELD,
        /** 站在这里够得着,放得进去。 */
        REACH,
        /** 站在这里够不着。 */
        FAR
    }

    private final NumenPlayer player;
    private final BuildTaskRecord r;
    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildLedger ledger;
    private final boolean instaBreak;
    /** 她此刻站的节点;悬在半空时为 null,什么都算够不着。 */
    private final Feet feet;

    BuildSurvey(NumenPlayer player, BuildTaskRecord r, BuildCellRules rules, BuildInventory inv, BuildLedger ledger) {
        this.player = player;
        this.r = r;
        this.rules = rules;
        this.inv = inv;
        this.ledger = ledger;
        this.instaBreak = WorkProfile.of(player).instaBreak();
        this.feet = Feet.of(player);
    }

    /** 给查询用:不派活,按这一份施工图与她此刻站的地方数一遍。 */
    public static BuildSurvey of(NumenPlayer player, BuildTaskRecord r) {
        BuildCellRules rules = new BuildCellRules(player, r);
        BuildInventory inv = new BuildInventory(player);
        BuildLedger ledger = new BuildLedger(player, r, rules, inv, new BuildFixtures(player, r, inv));
        return new BuildSurvey(player, r, rules, inv, ledger);
    }

    /** 这一格此刻的情形。 */
    public State of(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState now = rules.peek(pos);
        if (target.matches(now) || target.desiredState().getBlock() instanceof LiquidBlock
                || (BuildCellRules.isAirTarget(target) && now.getBlock() instanceof LiquidBlock)) {
            return State.DONE;
        }
        if (rules.refused(target) || rules.hopeless(target)) {
            return State.SKIPPED;
        }
        if (!instaBreak && DigTaskRecord.wants(null, now)) {
            return BuildCellRules.isAirTarget(target) || affordable(target) ? State.DIG : State.SHORT;
        }
        if (target.itemPlace() && !target.desiredState().canSurvive(player.level(), pos) && waitsOnNeighbour(pos)) {
            return State.UNHELD;
        }
        return reaches(pos) ? State.REACH : State.FAR;
    }

    /** 相邻六格里有还没盖好、也不是不去动的设计格。 */
    private boolean waitsOnNeighbour(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BuildTaskRecord.Target next = r.targetAt(pos.relative(side));
            if (next != null && !next.matches(rules.peek(next.pos())) && !rules.refused(next) && !rules.hopeless(next)) {
                return true;
            }
        }
        return false;
    }

    /** 站在这里够不够得着往这一格里放。 */
    boolean reaches(BlockPos pos) {
        return feet != null && feet.in(Destination.reach(player, pos));
    }

    /**
     * 她此刻付不付得起这一格:不花料的格永远付得起;有料单的格单子上每样都在,否则身上够件数。施工逐格扣料前问的也是它。
     */
    boolean affordable(BuildTaskRecord.Target target) {
        int cost = r.consumeMaterials && rules.costsMaterial(target) ? target.materialCount() : 0;
        if (cost <= 0) {
            return true;
        }
        var needs = ledger.needsFor(target);
        if (needs.isEmpty()) {
            return inv.hasItems(target.item(), cost, true);
        }
        for (BuildTaskRecord.CellNeed need : needs) {
            if (inv.countMatching(need) < 1) {
                return false;
            }
        }
        return true;
    }

    /** 数一遍整份:每种情形几格,以及要挖的格(离她近的在前)与够不着的格(低的在前、同一层里近的在前)。 */
    public Tally tally() {
        Map<State, Integer> counts = new EnumMap<>(State.class);
        List<BlockPos> dig = new ArrayList<>();
        List<BlockPos> far = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            State state = of(target);
            counts.merge(state, 1, Integer::sum);
            if (state == State.DIG) {
                dig.add(target.pos());
            } else if (state == State.FAR) {
                far.add(target.pos());
            }
        }
        BlockPos here = player.blockPosition();
        dig.sort(Comparator.comparingDouble(p -> p.distSqr(here)));
        far.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> p.distSqr(here)));
        return new Tally(counts, dig, far);
    }

    /**
     * 数出来的结果。
     *
     * @param dig 要先挖开的格,离她近的在前
     * @param far 够不着的格,低的在前、同一层里离她近的在前:下面的先盖,上面的才有地方站
     */
    public record Tally(Map<State, Integer> counts, List<BlockPos> dig, List<BlockPos> far) {

        public int count(State state) {
            return counts.getOrDefault(state, 0);
        }

        /** 还没了结的格:不算已经对上的,也不算不去动的。 */
        public int left() {
            return count(State.DIG) + count(State.SHORT) + count(State.REACH) + count(State.UNHELD) + count(State.FAR);
        }
    }
}
