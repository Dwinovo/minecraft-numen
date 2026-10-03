package com.dwinovo.numen.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ApiError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NamedAreas;
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
 * <p>路线跟着主人({@link Routes}):同一个主人的同伴都认得、都能改。每次调用都点名路线,没点名的是她自己的那条
 * ({@link Itinerary#gotoOf}),没有"当前路线"。改意图的每一步丢掉旧计划——计划是对着旧意图做的;回执说清下一步是
 * {@code route.plan}。
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
            throw new ApiError(ErrorKind.NOT_FOUND, "there is no route named " + name + "; route.new makes one",
                    "route.list()");
        }
        return route;
    }

    /**
     * 新建一条:从她站的地方到 {@code to},带着这次调用写的路线标志。去处写错、标志点名的区域不在,当场提醒。
     *
     * @param own 这是她自己的那条({@link Itinerary#gotoOf}):每一趟都换掉它;别的名字已经有了就不新建
     */
    public static String create(NumenPlayer her, String name, boolean own, Destination.Stop to, CommandArgs args) {
        if (!own && routes(her).get(name) != null) {
            return TaskResult.fail(ErrorKind.FAILED, "there is already a route named " + name + "; route.show(\""
                    + name + "\") shows it", "route.delete(\"" + name + "\")").toJson();
        }
        String flags = RouteFlags.written(args);
        Itinerary route = Itinerary.of(name, her.level().dimension().location(), to, flags);
        NamedAreas areas = NamedAreas.of(her);
        Destination.of(her, to, RouteFlags.spec(RouteFlags.base(her), route, 0, areas), areas, Feet.cell(her));
        routes(her).put(route);
        String call = own ? "" : "\"" + name + "\"";
        return TaskResult.ok("made route " + name + ": from wherever I stand to " + to.words()
                + (flags.isEmpty() ? ", changing no block" : ", with " + RouteFlags.shown(flags))
                + ". `route.plan(" + call + ")` plans it without moving and lists every block it would change; "
                + "`move.go(" + call + ")` then walks it.", RouteText.info(route)).toJson();
    }

    /** 插一个途经点,成为第 {@code at} 个;没给就插在终点前面。 */
    public static String via(NumenPlayer her, String name, Destination.Stop stop, Integer at) {
        Itinerary route = named(her, name);
        int place = at == null ? route.legs().size() : at;
        Itinerary next = route.via(stop, place);
        NamedAreas areas = NamedAreas.of(her);
        Destination.of(her, stop, RouteFlags.spec(RouteFlags.base(her), next, place - 1, areas), areas, Feet.cell(her));
        return saved(her, next, "added stop " + place + " (" + stop.words() + ")");
    }

    /** 删掉第 {@code n} 个途经点;没给就是最后一个(终点前面那一个)。 */
    public static String drop(NumenPlayer her, String name, Integer n) {
        Itinerary route = named(her, name);
        if (n == null && route.legs().size() == 1) {
            throw new IllegalArgumentException("route " + name + " has no waypoint, only its destination; "
                    + "`route.delete(\"" + name + "\")` removes the whole route");
        }
        int stop = n == null ? route.legs().size() - 1 : n;
        return saved(her, route.dropVia(stop), "dropped stop " + stop);
    }

    /** 改规格:整条,或第 {@code leg} 段;写了的标志换掉原来的,没写的照旧。 */
    public static String spec(NumenPlayer her, String name, Integer leg, CommandArgs args) {
        Itinerary route = named(her, name);
        if (!RouteSpecFlags.given(args)) {
            throw new IllegalArgumentException("give at least one route flag to change, e.g. `route.spec(\"" + name
                    + "\", {alter = \"natural\"})`; `route.show(\"" + name + "\")` shows the flags it has");
        }
        Itinerary next;
        if (leg == null) {
            next = route.withFlags(RouteFlags.merged(name, route.flags(), args, NamedAreas.of(her)));
        } else {
            if (leg < 1 || leg > route.legs().size()) {
                throw new IllegalArgumentException("route " + name + " has " + route.legs().size() + " leg(s); leg "
                        + leg + " is not one of them");
            }
            next = route.withLegFlags(leg, RouteFlags.merged(name, route.legs().get(leg - 1).flags(), args,
                    NamedAreas.of(her)));
        }
        return saved(her, next, "changed the flags" + (leg == null ? "" : " of leg " + leg));
    }

    /** 改过意图的一条存下,回执报出现在的样子与下一步。 */
    private static String saved(NumenPlayer her, Itinerary route, String what) {
        routes(her).put(route);
        return TaskResult.ok(what + ". " + RouteText.intent(route) + " Its old plan is dropped; `route.plan(\""
                + route.name() + "\")` plans it again.", RouteText.info(route)).toJson();
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
        JsonObject data = RouteText.planData(route, plan);
        if (plan.unreachable() < 0) {
            return TaskResult.ok(text, data).toJson();
        }
        Map<String, Object> failed = new java.util.LinkedHashMap<>();
        data.entrySet().forEach(e -> failed.put(e.getKey(), e.getValue()));
        return TaskResult.fail(ErrorKind.NO_PATH, text, null, failed).toJson();
    }

    /** 展示一条:意图、计划、走过的记录。 */
    public static String show(NumenPlayer her, String name) {
        Itinerary route = named(her, name);
        long now = now(her);
        StringBuilder sb = new StringBuilder(RouteText.intent(route)).append('\n');
        sb.append(route.plan() == null ? "Not planned yet: `route.plan(\"" + name + "\")` plans it."
                : RouteText.plan(route, route.plan(), now));
        String walks = RouteText.walks(route, now);
        if (!walks.isEmpty()) {
            sb.append('\n').append(walks);
        }
        JsonObject data = new JsonObject();
        data.add("route", RouteText.info(route));
        if (route.plan() != null) {
            data.add("plan", RouteText.planData(route, route.plan()));
        }
        return TaskResult.ok(sb.toString(), data).toJson();
    }

    /** 主人的全部路线,一条一行。 */
    public static String list(NumenPlayer her, CommandArgs args) {
        long now = now(her);
        List<String> rows = new ArrayList<>();
        JsonArray all = new JsonArray();
        for (Itinerary route : routes(her).all()) {
            rows.add(RouteText.row(route, now));
            all.add(RouteText.info(route));
        }
        String head = rows.isEmpty() ? "No routes yet: `route.new` makes one, and move.goto_ keeps each walk as your "
                + "own route " + Itinerary.gotoOf(her.getGameProfile().getName()) + "."
                : "Routes of your owner, shared by all of their companions:";
        return new Listing(head, rows, "").result(args, Map.of("routes", all)).toJson();
    }

    /** 删掉一条。 */
    public static String delete(NumenPlayer her, String name) {
        named(her, name);
        routes(her).remove(name);
        return TaskResult.ok("deleted route " + name).toJson();
    }

    /** 反着的一条,叫 {@code as}:从这一条的终点回到它上次规划时的起点。 */
    public static String reverse(NumenPlayer her, String name, String given) {
        String as = given != null ? given : name + "_back";
        if (routes(her).get(as) != null) {
            return TaskResult.fail(ErrorKind.FAILED, "there is already a route named " + as + "; pick another name for "
                    + "as", null).toJson();
        }
        Itinerary back = named(her, name).reversed(as);
        routes(her).put(back);
        return TaskResult.ok("made route " + as + ", " + name + " the other way. " + RouteText.intent(back)
                + " `route.plan(\"" + as + "\")` plans it.", RouteText.info(back)).toJson();
    }
}
