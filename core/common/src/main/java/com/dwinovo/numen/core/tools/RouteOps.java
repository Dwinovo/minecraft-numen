package com.dwinovo.numen.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.RouteQueries;
import com.dwinovo.numen.core.route.Itinerary;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.RouteFlags;
import com.dwinovo.numen.core.route.RoutePlanning;
import com.dwinovo.numen.core.route.RouteText;
import com.dwinovo.numen.core.route.Routes;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;

/**
 * 路线这一组:新建、插途经点、删途经点、改规格、规划、展示、列出、删除、反着来一条。除了规划都当场回;规划不占身体,
 * 在后台只搜不走,出结论那一刻回复({@link RouteQueries})。都不动世界一格。
 *
 * <p>路线跟着主人({@link Routes}):同一个主人的同伴都认得、都能改。每一行都点名路线,没有"当前路线"。改意图的每一步
 * 丢掉旧计划——计划是对着旧意图做的;回执说清下一步是 {@code route plan}。
 */
public final class RouteOps {

    private RouteOps() {}

    private static Routes routes(NumenPlayer her) {
        return Routes.of(her.getServer(), her.getOwnerUuid());
    }

    private static long now(NumenPlayer her) {
        return her.getServer().overworld().getGameTime();
    }

    /** 叫这个名字的那条;没有就说清怎么看有哪些。 */
    private static Itinerary named(NumenPlayer her, String name) {
        Itinerary route = routes(her).get(name);
        if (route == null) {
            throw new IllegalArgumentException("there is no route named " + name + "; route list shows the routes you "
                    + "have, route new makes one");
        }
        return route;
    }

    /**
     * {@code --to} 的几个数写成去处:三个是一格,两个是一处(x z),一个是一个高度。
     *
     * @throws IllegalArgumentException 个数不对,或到达方式与坐标对不上
     */
    public static Destination.Stop stop(List<Integer> to, String arrive, Integer near) {
        return switch (to.size()) {
            case 3 -> Destination.Stop.of(to.get(0), to.get(1), to.get(2), arrive, near);
            case 2 -> Destination.Stop.of(to.get(0), null, to.get(1), arrive, near);
            case 1 -> Destination.Stop.of(null, to.get(0), null, arrive, near);
            default -> throw new IllegalArgumentException("--to takes x y z (one cell), x z (a place) or y (a height); "
                    + "got " + to.size() + " numbers");
        };
    }

    /** 新建一条:从她站的地方到 {@code to},带着这一行写的路线标志。去处写错当场提醒,和 {@code move goto} 一样。 */
    public static String create(NumenPlayer her, String name, Destination.Stop to, CommandArgs args) {
        if (routes(her).get(name) != null) {
            return TaskResult.fail("there is already a route named " + name + "; route show " + name + " shows it, "
                    + "route delete " + name + " removes it").toJson();
        }
        String flags = RouteFlags.written(args);
        Itinerary route = Itinerary.of(name, her.level().dimension().location(), to, flags);
        Destination.of(her, to, RouteFlags.spec(route, 0));
        routes(her).put(route);
        return TaskResult.ok("made route " + name + ": from wherever I stand to " + to.words()
                + (flags.isEmpty() ? ", changing no block" : ", with " + flags)
                + ". route plan " + name + " plans it without moving and lists every block it would change; move go "
                + name + " plans and walks it.").toJson();
    }

    /** 插一个途经点,成为第 {@code at} 个;没给就插在终点前面。 */
    public static String via(NumenPlayer her, String name, Destination.Stop stop, Integer at) {
        Itinerary route = named(her, name);
        int place = at == null ? route.legs().size() : at;
        Itinerary next = route.via(stop, place);
        Destination.of(her, stop, RouteFlags.spec(next, place - 1));
        return saved(her, next, "added stop " + place + " (" + stop.words() + ")");
    }

    /** 删掉第 {@code n} 个途经点。 */
    public static String drop(NumenPlayer her, String name, int n) {
        return saved(her, named(her, name).dropVia(n), "dropped stop " + n);
    }

    /** 改规格:整条,或第 {@code leg} 段;写了的标志换掉原来的,没写的照旧。 */
    public static String spec(NumenPlayer her, String name, Integer leg, CommandArgs args) {
        Itinerary route = named(her, name);
        if (!RouteSpecFlags.given(args)) {
            throw new IllegalArgumentException("give at least one route flag to change, e.g. route spec " + name
                    + " --alter natural; route show " + name + " shows the flags it has");
        }
        Itinerary next;
        if (leg == null) {
            next = route.withFlags(RouteFlags.merged(name, route.flags(), args));
        } else {
            if (leg < 1 || leg > route.legs().size()) {
                throw new IllegalArgumentException("route " + name + " has " + route.legs().size() + " leg(s); leg "
                        + leg + " is not one of them");
            }
            next = route.withLegFlags(leg, RouteFlags.merged(name, route.legs().get(leg - 1).flags(), args));
        }
        return saved(her, next, "changed the flags" + (leg == null ? "" : " of leg " + leg));
    }

    /** 改过意图的一条存下,回执报出现在的样子与下一步。 */
    private static String saved(NumenPlayer her, Itinerary route, String what) {
        routes(her).put(route);
        return TaskResult.ok(what + ". " + RouteText.intent(route) + " Its old plan is dropped; route plan "
                + route.name() + " plans it again.").toJson();
    }

    /**
     * 规划:从她脚下只搜不走,不占身体。计划记在路线上(它就是 {@code move go} 要守的承诺),结论出来那一刻回复;第一段的去处
     * 此刻就编不成目标时当场回。
     */
    public static void plan(ServerSource src, String name) {
        NumenPlayer her = src.companion();
        Itinerary route = named(her, name);
        RoutePlanning planning = RoutePlanning.of(her, route);
        if (planning.survey() == null) {
            src.reply(planned(her, route, planning.result(List.of())));
            return;
        }
        RouteQueries.deliver(planning.survey(), found -> src.reply(planned(her, route, planning.result(found))));
    }

    /** 规划的回执:计划记进路线,照计划说;有走不通的段是失败。 */
    private static String planned(NumenPlayer her, Itinerary route, RoutePlanning.Result result) {
        Plan plan = result.plan();
        routes(her).plan(route, plan);
        String text = RouteText.plan(route, plan, now(her));
        Map<String, Object> data = Map.of("route", route.name(), "walkable", plan.unreachable() < 0);
        return (plan.unreachable() < 0 ? TaskResult.ok(text, data) : TaskResult.fail(text, data)).toJson();
    }

    /** 展示一条:意图、计划、走过的记录。 */
    public static String show(NumenPlayer her, String name) {
        Itinerary route = named(her, name);
        long now = now(her);
        StringBuilder sb = new StringBuilder(RouteText.intent(route)).append('\n');
        sb.append(route.plan() == null ? "Not planned yet: route plan " + name + " plans it."
                : RouteText.plan(route, route.plan(), now));
        String walks = RouteText.walks(route, now);
        if (!walks.isEmpty()) {
            sb.append('\n').append(walks);
        }
        return TaskResult.ok(sb.toString()).toJson();
    }

    /** 主人的全部路线,一条一行。 */
    public static String list(NumenPlayer her, CommandArgs args) {
        long now = now(her);
        List<String> rows = new ArrayList<>();
        for (Itinerary route : routes(her).all()) {
            rows.add(RouteText.row(route, now));
        }
        String head = rows.isEmpty() ? "No routes yet: route new makes one, and move goto keeps each walk as your "
                + "own route " + Itinerary.gotoOf(her.getGameProfile().getName()) + "."
                : "Routes of your owner, shared by all of their companions:";
        return new Listing(head, rows, "", Itinerary.GROUP + " list").result(args).toJson();
    }

    /** 删掉一条。 */
    public static String delete(NumenPlayer her, String name) {
        named(her, name);
        routes(her).remove(name);
        return TaskResult.ok("deleted route " + name).toJson();
    }

    /** 反着的一条,叫 {@code as}:从这一条的终点回到它上次规划时的起点。 */
    public static String reverse(NumenPlayer her, String name, String as) {
        if (as == null) {
            throw new IllegalArgumentException("route reverse needs --as <name> for the new route, e.g. route reverse "
                    + name + " --as " + name + "_back");
        }
        if (routes(her).get(as) != null) {
            return TaskResult.fail("there is already a route named " + as + "; pick another name for --as").toJson();
        }
        Itinerary back = named(her, name).reversed(as);
        routes(her).put(back);
        return TaskResult.ok("made route " + as + ", " + name + " the other way. " + RouteText.intent(back)
                + " route plan " + as + " plans it.").toJson();
    }
}
