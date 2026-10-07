package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.sdk.BlockAt;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Flatten;
import com.dwinovo.numen.sdk.Folded;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * 计划怎么交给她:程序拿到的值({@link #data},样子是 {@link Plan})只在这里写出。要改的格与实际账同一种写法({@link NavText#planned});
 * 走不通的原因是寻路结局的原话,规划时已经写进计划。这里的 {@link Plan} 是交给程序的那一份,算出来的那份是
 * {@link com.dwinovo.numen.core.route.Plan}。
 */
public final class RouteText {

    private RouteText() {}

    /** 路上的一步怎么走。 */
    public enum Move { WALK, JUMP, FALL, SWIM, CLIMB, DIG, PLACE, SAIL }

    /** 路上的一步。 */
    @Doc("One step of a planned way.")
    public record Step(@Doc("Where the step ends: the cell your feet are in.") BlockPos pos,
                       @Doc("How: walk, jump, fall, swim, climb, dig (breaks its way), place (pillars or bridges), sail "
                               + "(in the boat).") Move move) {}

    /** 要问主人的一格。 */
    @Doc("A cell the walk changes that needs your owner's consent: a Block with why. The walk stops there and asks; "
            + "give it to avoid (avoid = {ask}) to plan around it instead.")
    public record Ask(@Flatten BlockAt block,
                      @Doc("Why it needs consent, in the permission layer's words (placed by a player …).") String why) {}

    /** 一段水下没有气的路。 */
    @Doc("A stretch under water with no air on the way.")
    public record Dive(BlockPos from, BlockPos to,
                       @Doc("Without a breath.") double seconds,
                       @Doc("Seconds of air left after it.") double spare) {}

    /** 计划里的一段。 */
    @Doc("One leg of a plan: the way to one stop.")
    public record Leg(@Doc("The cell it heads for.") Optional<BlockPos> to,
                      @Doc("reached: seen all the way. partial: seen as far as one look reaches (or the water ends), "
                              + "worked out on the way. unreachable: can't be walked as described (why). unplanned: "
                              + "after a partial or unreachable leg, not planned.")
                      com.dwinovo.numen.core.route.Plan.Reach reach,
                      @Doc("Where the seen part ends.") Optional<BlockPos> finish,
                      int steps,
                      @Doc("About how long, as priced.") double seconds,
                      @Doc("Step by step; not printed with the plan, read it as leg.path.") @Folded List<Step> path,
                      @Doc("Blocks it breaks.") List<BlockAt> breaks,
                      @Doc("Blocks it places (a water bucket poured to break a fall shows as water).")
                      List<BlockAt> places,
                      @Doc("Of those, the cells your owner is asked about when you get there.") List<Ask> asks,
                      @Doc("Stretches under water with no air on the way.") Optional<List<Dive>> dives,
                      @Doc("Why it can't be walked, or why only part was seen.") Optional<String> why) {}

    /** 一份计划:{@code numen.route.plan} 交回的值,{@code numen.move.go} 收它。 */
    @Doc("A walk planned from where you stood, without moving: what numen.move.go walks and keeps to. Good only within "
            + "the program that made it.")
    public record Plan(@Doc("No leg is unreachable: numen.move.go can walk it.") boolean ok,
                       @Doc("When not ok: why the unreachable leg can't be walked.") Optional<String> why,
                       @Doc("Which plan this is, for numen.move.go.") String id,
                       @Doc("The description you gave, as given: change it and plan again.")
                       Description.Directions spec,
                       @Doc("Where it was planned from.") BlockPos from,
                       @Doc("Steps of the seen part, all legs.") int steps,
                       @Doc("About how long the seen part takes.") double seconds,
                       @Doc("One per stop, the destination last.") List<Leg> legs) {}

    // ==================== 数据 ====================

    /** 程序拿到的那份。 */
    public static Plan data(com.dwinovo.numen.core.route.Plan plan) {
        return new Plan(plan.ok(), plan.ok() ? Optional.empty() : Optional.of(plan.why()), plan.id(),
                plan.description().written(), plan.from(), plan.steps(), plan.seconds(),
                plan.legs().stream().map(RouteText::legData).toList());
    }

    private static Leg legData(com.dwinovo.numen.core.route.Plan.Leg leg) {
        BlockPos toward = leg.to() != null ? leg.to().toward()
                : leg.stop().to() instanceof Target.Cell cell ? cell.pos() : null;
        NavText.Changes changes = leg.changes();
        List<Ask> asks = new ArrayList<>();
        changes.asks().forEach((pos, cause) -> {
            Block block = changes.digs().containsKey(pos) ? changes.digs().get(pos) : changes.places().get(pos);
            asks.add(new Ask(BlockAt.of(pos, block.defaultBlockState()), cause));
        });
        List<Dive> dives = new ArrayList<>();
        if (leg.route() != null) {
            for (Route.Dive dive : leg.route().dives()) {
                dives.add(new Dive(dive.from(), dive.to(), Math.round(dive.held() / 2.0) / 10.0,
                        Math.round(dive.left() / 2.0) / 10.0));
            }
        }
        return new Leg(Optional.ofNullable(toward), leg.reach(), Optional.ofNullable(leg.finish()), leg.steps(),
                Math.round(leg.ticks() / 2.0) / 10.0, path(leg), blocks(changes.digs()), blocks(changes.places()),
                asks, dives.isEmpty() ? Optional.empty() : Optional.of(dives),
                leg.why().isEmpty() ? Optional.empty() : Optional.of(leg.why()));
    }

    /** 一步步:每一步落在哪一格、怎么走。 */
    private static List<Step> path(com.dwinovo.numen.core.route.Plan.Leg leg) {
        List<Step> out = new ArrayList<>();
        if (leg.chart() != null) {
            for (BlockPos cell : leg.chart().path()) {
                out.add(new Step(cell.immutable(), Move.SAIL));
            }
        } else if (leg.route() != null) {
            for (Route.Leg step : leg.route().legs()) {
                out.add(new Step(step.maneuver().to().immutable(), move(step.maneuver())));
            }
        }
        return out;
    }

    /** 一步怎么走,说给她的那个词:手上要放的是 place,要挖的是 dig,其余照身体怎么挪。 */
    static Move move(Maneuver m) {
        boolean places = m.edits().stream().anyMatch(e -> e instanceof Edit.Place || e instanceof Edit.Catch);
        boolean digs = m.edits().stream().anyMatch(e -> e instanceof Edit.Dig);
        if (places) {
            return Move.PLACE;
        }
        if (digs) {
            return Move.DIG;
        }
        return switch (m.kind()) {
            case WALK, DIAGONAL, DESCEND -> Move.WALK;
            case ASCEND -> m.jump() ? Move.JUMP : Move.WALK;
            case PARKOUR -> Move.JUMP;
            case FALL -> Move.FALL;
            case PILLAR -> Move.PLACE;
            case DOWNWARD -> Move.DIG;
            case CLIMB -> Move.CLIMB;
            case SWIM -> Move.SWIM;
        };
    }

    private static List<BlockAt> blocks(Map<BlockPos, Block> cells) {
        List<BlockAt> out = new ArrayList<>();
        cells.forEach((pos, block) -> out.add(BlockAt.of(pos, block.defaultBlockState())));
        return out;
    }

    // ==================== 话 ====================

    /** 从这里规划出来的路超出承诺的那些格,一句。 */
    public static String beyond(com.dwinovo.numen.core.route.Plan.Difference diff) {
        List<String> parts = new ArrayList<>();
        if (!diff.digs().isEmpty() || !diff.places().isEmpty()) {
            parts.add(NavText.planned(cells(diff.digs()), cells(diff.places()), Map.of()));
        }
        if (!diff.asks().isEmpty()) {
            List<BlockPos> at = diff.asks().stream().map(com.dwinovo.numen.core.route.Plan.Ask::pos).toList();
            parts.add("ask your owner about " + Listing.part("cell(s)", at.size(), at));
        }
        return String.join("; ", parts);
    }

    private static Map<BlockPos, Block> cells(List<com.dwinovo.numen.core.route.Plan.Cell> cells) {
        Map<BlockPos, Block> out = new LinkedHashMap<>();
        cells.forEach(c -> out.put(c.pos(), c.block()));
        return out;
    }
}
