package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.nav.BoatNav;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Survey;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;

/**
 * 规划一趟路:按此刻的世界把每一站编成目标({@link Destination#of})、按描述的规格({@link Description#spec}),从她脚下起逐段只搜
 * 不走({@link Survey});驾船的一趟在水面上逐段画航线({@link BoatNav#chart})。出结论时写成计划({@link Plan})。{@code numen.route.plan}、
 * {@code numen.move.go} 从别处出发时的重新规划、半路停下后从这里看剩下几段,都是这一处。
 *
 * <p>一站此刻就编不成目标(站不进去、没东西可用、点名的实体不在了),那一段就是走不通,后面的不规划;不搜。
 */
public final class Planning {

    private final NumenPlayer her;
    private final String id;
    private final Description description;
    private final int first;
    private final BlockPos from;
    /** 编好的每一段(从 {@link #first} 起);编不成目标的那一段与之后的没有。 */
    private final List<Compiled> compiled = new ArrayList<>();
    /** 编不成目标的那一段的提醒;都编成了为 null。 */
    private String refusal;
    private final Survey survey;

    /** 一段编好的样子:哪一站、去处、规格。 */
    private record Compiled(Stop stop, Destination to, RouteSpec spec) {}

    private Planning(NumenPlayer her, String id, Description description, int first) {
        this.her = her;
        this.id = id;
        this.description = description;
        this.first = first;
        this.from = Feet.cell(her);
        RouteSpec spec = description.spec(base(her));
        // 每一段从上一段去的那一格算起:一堆格子按离它的远近挑成员,坐标缺的那一截照它补
        BlockPos start = from;
        for (int i = first; i < description.stops().size(); i++) {
            Stop stop = description.stops().get(i);
            try {
                Destination to = Destination.of(her, stop, spec, start);
                compiled.add(new Compiled(stop, to, spec));
                start = to.toward();
            } catch (IllegalArgumentException e) {
                refusal = e.getMessage();
                break;
            }
        }
        this.survey = description.mode() == Description.Mode.WALK && !compiled.isEmpty()
                ? Survey.of(her, compiled.stream().map(c -> new Survey.Leg(c.to().goal(), c.spec())).toList(),
                        description.materials())
                : null;
    }

    /** 从她脚下规划整趟。 */
    public static Planning of(NumenPlayer her, String id, Description description) {
        return new Planning(her, id, description, 0);
    }

    /** 从她脚下规划 {@code plan} 第 {@code first} 段(从 0 数)起的剩下几段:半路停下时看"从这里接着走要什么"。 */
    public static Planning rest(NumenPlayer her, Plan plan, int first) {
        return new Planning(her, plan.id(), plan.description(), first);
    }

    /** 有结论了就交出,没有为 null。每刻调一次。 */
    public Plan poll() {
        if (description.mode() == Description.Mode.BOAT) {
            return sailed();
        }
        if (survey == null) {
            return walked(List.of());
        }
        List<Survey.Found> found = survey.poll();
        return found == null ? null : walked(found);
    }

    /** 不要了。 */
    public void cancel() {
        if (survey != null) {
            survey.cancel();
        }
    }

    /**
     * 她每一段路的底子:出厂值,加上盖好的房子不挖——{@link Built} 记着、此刻还立着的格(她照设计放下的)描述里哪一项都放不开,路线
     * 只许绕开、从上面走或站在旁边够。房子是要留下来的东西,不是挡路的地形:许挖时路上挖掉一格墙,下一轮又得补上,建造就在原地
     * 来回打转。在主线程上调。
     */
    public static RouteSpec base(NumenPlayer her) {
        LongSet built = Built.of(her.getServer()).standingIn(her.serverLevel());
        if (built.isEmpty()) {
            return RouteSpec.defaults();
        }
        return RouteSpec.defaults().edit()
                .positions(PositionCosts.builder().forbid(PositionCosts.Use.DIG, built::contains).build()).build();
    }

    /** 走路的一趟:规划出来的 {@code found} 写成计划。 */
    private Plan walked(List<Survey.Found> found) {
        List<Plan.Leg> legs = new ArrayList<>();
        BlockPos start = from;
        boolean seen = true;
        for (int i = first; i < description.stops().size(); i++) {
            int k = i - first;
            Stop stop = description.stops().get(i);
            if (k == compiled.size() && refusal != null) {
                // 这一站此刻就编不成目标:与从哪儿走来无关,确定走不通
                legs.add(Plan.Leg.bare(stop, null, null, Plan.Reach.UNREACHABLE, null, refusal));
                seen = false;
            } else if (!seen || k >= found.size()) {
                legs.add(unplanned(stop, k));
                seen = false;
            } else {
                Survey.Found f = found.get(k);
                Compiled c = compiled.get(k);
                if (f.reached()) {
                    legs.add(Plan.Leg.walked(stop, c.to(), c.spec(), Plan.Reach.REACHED, f.route(), ""));
                    start = f.route().end();
                    continue;
                }
                String why = NavText.failure(f.outcome(), her, start, c.to().toward(), c.spec(),
                        description.materials());
                legs.add(unknown(f.outcome())
                        ? Plan.Leg.walked(stop, c.to(), c.spec(), Plan.Reach.PARTIAL, f.partial(), why)
                        : Plan.Leg.bare(stop, c.to(), c.spec(), Plan.Reach.UNREACHABLE, null, why));
                seen = false;
            }
        }
        return new Plan(id, description, from, legs);
    }

    /** 驾船的一趟:从船所在的那一格起,逐站在水面上画航线;没坐在船上就走不通。 */
    private Plan sailed() {
        List<Plan.Leg> legs = new ArrayList<>();
        BlockPos start = her.getVehicle() instanceof Boat boat ? boat.blockPosition() : null;
        boolean seen = true;
        for (int i = first; i < description.stops().size(); i++) {
            int k = i - first;
            Stop stop = description.stops().get(i);
            if (k == compiled.size() && refusal != null) {
                legs.add(Plan.Leg.bare(stop, null, null, Plan.Reach.UNREACHABLE, null, refusal));
                seen = false;
            } else if (!seen) {
                legs.add(unplanned(stop, k));
            } else if (start == null) {
                legs.add(Plan.Leg.bare(stop, compiled.get(k).to(), compiled.get(k).spec(), Plan.Reach.UNREACHABLE,
                        null, "I am not sitting in a boat: mode = \"boat\" plans a sail only from a boat "
                        + "(numen.use.entity on one boards it)"));
                seen = false;
            } else {
                Compiled c = compiled.get(k);
                BoatNav.Chart chart = BoatNav.chart(her, start, c.to().toward());
                if (chart.why() != null) {
                    legs.add(Plan.Leg.bare(stop, c.to(), c.spec(), Plan.Reach.UNREACHABLE, chart, chart.why()));
                    seen = false;
                } else if (chart.reached()) {
                    legs.add(Plan.Leg.bare(stop, c.to(), c.spec(), Plan.Reach.REACHED, chart, ""));
                    start = chart.end() != null ? chart.end() : start;
                } else {
                    legs.add(Plan.Leg.bare(stop, c.to(), c.spec(), Plan.Reach.PARTIAL, chart,
                            "the water ends at " + com.dwinovo.numen.permission.Listing.coords(chart.end())
                                    + ", the shore nearest " + stop.words() + "; from there it is on foot "
                                    + "(numen.move.dismount, then plan a walk)"));
                    seen = false;
                }
            }
        }
        return new Plan(id, description, from, legs);
    }

    /** 前面一段走不通或没看清:这一段编好了照它的目标边走边算,编不成就只有那一站。 */
    private Plan.Leg unplanned(Stop stop, int k) {
        Compiled c = k < compiled.size() ? compiled.get(k) : null;
        return Plan.Leg.bare(stop, c == null ? null : c.to(), c == null ? null : c.spec(), Plan.Reach.UNPLANNED, null,
                "");
    }

    /** 没走到只是因为没看完:预算用完、伸进了没加载的区块。别的结局是按这份描述走不通。 */
    public static boolean unknown(Outcome outcome) {
        return outcome instanceof Outcome.OutOfBudget || outcome instanceof Outcome.Unloaded;
    }
}
