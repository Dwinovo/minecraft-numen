package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Survey;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Bill;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentItem;

import net.minecraft.core.BlockPos;

/**
 * 规划一条路线:按此刻的世界把每一段的途经点编成目标({@link Destination#of})、按每一段的规格({@link RouteFlags#spec}),
 * 交给 {@link Survey} 从她脚下起逐段只搜不走;出结论时写成计划({@link Plan})——每段多长、多少刻、要挖要放要问的格,走不通或
 * 没看清的为什么。{@code route plan} 与 {@code move go} 共用这一处;{@code move go} 还要拿规划出的路开走。
 *
 * <p>路线里点名的区域(途经点与标志里的)按此刻主人名下的区域解析,一次取好({@link NamedAreas}),各段共用:区域删了、部分删了,
 * 引用它的那一段编不成,计划里那一段写的就是哪块区域不在了。
 */
public final class RoutePlanning {

    /**
     * 一段编好的样子:交给规划的那一段(目标与规格),加上编好的去处(给人说"朝哪儿"的那一格在它上面)。
     */
    public record Leg(Survey.Leg way, Destination to) {

        /** 给人说"朝哪儿"的那一格(回执里的方向与距离)。 */
        public BlockPos toward() {
            return to.toward();
        }
    }

    /**
     * 规划的结论。
     *
     * @param plan  写成的计划
     * @param legs  编好的每一段(从规划的第一段起;途经点编不成目标的那一段与之后的没有)。没走到、没规划的段也在,
     *              执行时照它的目标开走
     * @param found 每一段规划出来的样子,与 {@code legs} 同序;没走到的那一段是最后一个
     */
    public record Result(Plan plan, List<Leg> legs, List<Survey.Found> found) {

        /** 规划出来要问主人的格,开走之前一次问完。 */
        public List<ConsentItem> consents() {
            List<ConsentItem> out = new ArrayList<>();
            for (Survey.Found f : found) {
                Route route = f.reached() ? f.route() : f.partial();
                if (route == null) {
                    continue;
                }
                for (Bill.Consent c : Bill.of(route).consents()) {
                    if (c.credential() instanceof ConsentItem item) {
                        out.add(item);
                    }
                }
            }
            return out;
        }
    }

    private final NumenPlayer her;
    private final Itinerary route;
    private final int first;
    private final List<Leg> legs = new ArrayList<>();
    /** 编不成目标的那一段的提醒({@link Destination#of}、{@link RouteFlags#spec} 的原话);都编成了为 null。 */
    private String refusal;
    private final Survey survey;
    private final BlockPos from;
    private final long at;

    private RoutePlanning(NumenPlayer her, Itinerary route, int first) {
        this.her = her;
        this.route = route;
        this.first = first;
        this.from = Feet.cell(her);
        this.at = her.server.overworld().getGameTime();
        NamedAreas areas = NamedAreas.of(her);
        // 每一段从上一段去的那一格算起:区域按离它的远近挑成员,坐标缺的那一截照它补
        BlockPos start = from;
        for (int i = first; i < route.legs().size(); i++) {
            Destination.Stop stop = route.legs().get(i).to();
            try {
                RouteSpec spec = RouteFlags.spec(route, i, areas);
                Destination to = Destination.of(her, stop, spec, areas, start);
                legs.add(new Leg(new Survey.Leg(to.goal(), spec), to));
                start = to.toward();
            } catch (IllegalArgumentException e) {
                refusal = e.getMessage();
                break;
            }
        }
        List<Survey.Leg> asked = legs.stream().map(Leg::way).toList();
        this.survey = asked.isEmpty() ? null : Survey.of(her, asked);
    }

    /** 从她脚下规划整条。 */
    public static RoutePlanning of(NumenPlayer her, Itinerary route) {
        return new RoutePlanning(her, route, 0);
    }

    /** 从她脚下规划第 {@code first} 段(从 0 数)起的剩下几段:半路停下时看"从这里接着走要什么"。 */
    public static RoutePlanning from(NumenPlayer her, Itinerary route, int first) {
        return new RoutePlanning(her, route, first);
    }

    /** 在后台跑的那次规划;第一段就编不成目标时没有(结论当场就有,见 {@link #result})。 */
    public Survey survey() {
        return survey;
    }

    /** 有结论了就交出,没有为 null。每刻调一次。 */
    public Result poll() {
        if (survey == null) {
            return result(List.of());
        }
        List<Survey.Found> found = survey.poll();
        return found == null ? null : result(found);
    }

    /** 不要了。 */
    public void cancel() {
        if (survey != null) {
            survey.cancel();
        }
    }

    /** 规划出来的 {@code found} 写成计划。 */
    public Result result(List<Survey.Found> found) {
        List<Plan.Leg> planned = new ArrayList<>();
        BlockPos start = from;
        boolean seen = true;
        for (int i = first; i < route.legs().size(); i++) {
            int k = i - first;
            if (k == legs.size() && refusal != null) {
                // 途经点此刻就编不成目标:与从哪儿走来无关,确定走不通
                planned.add(Plan.Leg.unreachable(refusal));
                seen = false;
            } else if (!seen) {
                planned.add(Plan.Leg.unplanned());
            } else if (k < found.size()) {
                Survey.Found f = found.get(k);
                Leg leg = legs.get(k);
                if (f.reached()) {
                    planned.add(leg(Plan.Reach.WALKABLE, f.route(), ""));
                    start = f.route().end();
                    continue;
                }
                String why = NavText.failure(f.outcome(), her, start, leg.toward(), leg.way().spec(),
                        new NavText.OnRoute(route.name(), i + 1, route.legs().size()));
                planned.add(unknown(f.outcome())
                        ? leg(Plan.Reach.PARTIAL, f.partial(), why) : Plan.Leg.unreachable(why));
                seen = false;
            } else {
                planned.add(Plan.Leg.unplanned());
                seen = false;
            }
        }
        return new Result(new Plan(from, at, planned), List.copyOf(legs), List.copyOf(found));
    }

    /** 没走到只是因为没看完:预算用完、伸进了没加载的区块。别的结局是按这份规格走不通。 */
    public static boolean unknown(Outcome outcome) {
        return outcome instanceof Outcome.OutOfBudget || outcome instanceof Outcome.Unloaded;
    }

    /** 一段看清了的路写成计划里的一段:几步、多少刻、停在哪、要挖要放要问的格、要潜的水。 */
    private static Plan.Leg leg(Plan.Reach reach, Route route, String why) {
        if (route == null) {
            return new Plan.Leg(reach, 0, 0, null, List.of(), List.of(), List.of(), why, List.of());
        }
        NavText.Changes changes = NavText.Changes.of(route.edits());
        List<Plan.Cell> digs = new ArrayList<>();
        List<Plan.Cell> places = new ArrayList<>();
        List<Plan.Ask> asks = new ArrayList<>();
        changes.digs().forEach((pos, block) -> digs.add(new Plan.Cell(pos, block)));
        changes.places().forEach((pos, block) -> places.add(new Plan.Cell(pos, block)));
        changes.asks().forEach((pos, cause) -> asks.add(new Plan.Ask(pos, cause)));
        List<Plan.Dive> dives = new ArrayList<>();
        for (Route.Dive dive : route.dives()) {
            dives.add(new Plan.Dive(dive.from(), dive.to(), (int) Math.ceil(dive.held()), (int) Math.floor(dive.left())));
        }
        return new Plan.Leg(reach, route.legs().size(), (int) Math.round(route.cost()), route.end(), digs, places,
                asks, why, dives);
    }
}
