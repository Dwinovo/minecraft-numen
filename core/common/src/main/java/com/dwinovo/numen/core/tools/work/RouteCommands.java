package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.core.route.RouteText;
import com.dwinovo.numen.agent.script.ScriptType;
import java.util.List;
import java.util.stream.Stream;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.route.Itinerary;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.core.tools.RouteOps;
import com.dwinovo.numen.core.tools.RouteSpecFlags;

/**
 * {@code route}:寻路——路线这个名词的增删改查与规划。一条路线是意图(途经点、路线标志)加最近一次计划加走过的记录,跟着主人存盘;
 * {@code numen.route.plan} 只搜不走、不占身体,写明要改的格与要问主人的格;{@code numen.move.go} 才走,并且只改计划里列出的格。设计稿见
 * {@code docs/look-plan-act.md}。
 *
 * <p>无状态:每次调用都点名路线;不点名的是她自己的那条 {@code goto-<名字>}——库里的 {@code numen.move.goto_} 每次把一趟写成它
 * ({@code numen.route.new} 不给名字就替换它)、规划、再走。去处与到达方式的写法同一套({@link MoveCommands#ARRIVE}、
 * {@link MoveCommands#NEAR},对应到寻路目标只在 {@link Destination}),路线标志同一串({@link RouteSpecFlags})。
 */
public final class RouteCommands {

    private static final Param<String> NAME = Param.required("name", ArgType.word(), "The route.")
            .values("a route name, as `numen.route.list()` lists it");
    private static final Param<String> OWN = Param.optionalPositional("name", ArgType.word(), "The route.")
            .values("a route name, as `numen.route.list()` lists it")
            .whenOmitted("use your own route goto-<your name>");
    private static final Param<String> NEW_NAME = Param.optionalPositional("name", ArgType.word(),
            "Name of the new route: lowercase letters, digits, _ and -.")
            .whenOmitted("write it as your own route goto-<your name>, replacing the one there");
    private static final Param<List<Place>> TO = Param.optional("to", ArgType.list(ArgType.place()),
            "The destination: coordinates, or several cells (a cluster's blocks) to reach any one of (see arrive).")
            .whenOmitted("end where you stand now");
    private static final Param<List<Place>> WAYPOINT = Param.optional("at", ArgType.list(ArgType.place()),
            "The waypoint: coordinates, or several cells to reach any one of.")
            .whenOmitted("add where you stand now");
    private static final Param<Integer> AS_STOP = Param.optional("stop", ArgType.integer(1, 99),
            "Which stop it becomes, counting from 1; one past the last makes it the new destination.")
            .whenOmitted("put it just before the destination");
    private static final Param<Integer> DROPPED = Param.optional("stop", ArgType.integer(1, 99),
            "The waypoint's stop number, as numen.route.show numbers them.")
            .whenOmitted("drop the last waypoint, the one just before the destination");
    private static final Param<Integer> LEG = Param.optional("leg", ArgType.integer(1, 99),
            "Change only this leg, the stretch to stop N.")
            .whenOmitted("change the whole route");
    private static final Param<String> AS = Param.optional("as", ArgType.word(), "Name of the new, reversed route.")
            .whenOmitted("name it after this one with _back, e.g. mine_back");

    private static final ScriptType ROUTE = RouteText.ROUTE_CLASS.type();

    private RouteCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(Itinerary.GROUP, "Pathfinding: routes with waypoints and route flags, planned without "
                + "moving; numen.move.go walks a planned route.", RouteCommands::actions);
    }

    /** 一个动作的参数表:自己的几个,再接上共用的那几串。 */
    @SafeVarargs
    private static Param<?>[] with(List<? extends Param<?>>... parts) {
        return Stream.of(parts).flatMap(List::stream).toArray(Param<?>[]::new);
    }

    /** 一处或几格写成去处:没写就是她此刻脚下那一格。 */
    private static Destination.Stop stop(ServerSource src, List<Place> written, CommandArgs args) {
        List<Place> places = written != null ? written : List.of(Place.cell(Feet.cell(src.companion())));
        return Destination.Stop.of(places, args.get(MoveCommands.ARRIVE), args.get(MoveCommands.NEAR));
    }

    /** 点名的那条;没点名是她自己的那条。 */
    private static String own(ServerSource src, String named) {
        return named != null ? named : Itinerary.gotoOf(src.companion().getGameProfile().getName());
    }

    private static void actions(CommandGroup route) {
        route.declare(RouteText.ROUTE_CLASS);
        route.declare(RouteText.PLAN_CLASS);
        route.server("new", "Make a route: from wherever you stand to a destination, with the route flags it walks "
                        + "under.", (src, args) -> src.reply(RouteOps.create(src.companion(), own(src,
                        args.get(NEW_NAME)), args.get(NEW_NAME) == null, stop(src, args.get(TO), args), args)),
                        with(List.of(NEW_NAME, TO, MoveCommands.ARRIVE, MoveCommands.NEAR), RouteSpecFlags.PARAMS))
                .returns(ROUTE)
                .example("numen.route.new(\"home\", {to = {x = 120, y = 64, z = -35}})")
                .example("numen.route.new(\"back\")")
                .example("numen.route.new({to = {{x = 120, y = 12, z = -35}, {x = 121, y = 12, z = -35}}, arrive = \"dig\", "
                        + "alter = \"natural\"})")
                .example("numen.route.new(\"ore\", {to = {x = 120, y = 12, z = -35}, arrive = \"dig\", "
                        + "alter = \"natural\", avoid_break = {{x = 121, y = 12, z = -35}}})")
                .note("Instant; it only writes the route down, nothing moves. to is a cell (a Pos, or anything with a "
                        + "pos), a column {x = …, z = …}, a height {y = …}, or several cells (a cluster's blocks) to "
                        + "reach any one of; arrive says what counts as there. A destination that cannot mean anything "
                        + "here is refused at once with the reason and the ways to write it: arrive at into a solid "
                        + "block or mid-air on a walk that changes nothing, arrive use or dig without y or on air. "
                        + "Without to the route ends where you stand now: a way back here.")
                .note("Without a name it is your own route goto-<your name>, replaced every time; a named route that "
                        + "already exists is refused.")
                .note("Without route flags the route changes no block. Routes belong to your owner: every companion "
                        + "of theirs sees and walks the same ones, and they survive restarts.")
                .seeAlso("route plan", "move go", "move goto_");
        route.server("via", "Add a waypoint to a route.", (src, args) -> src.reply(RouteOps.via(src.companion(),
                        args.get(NAME), stop(src, args.get(WAYPOINT), args), args.get(AS_STOP))),
                        NAME, WAYPOINT, MoveCommands.ARRIVE, MoveCommands.NEAR, AS_STOP)
                .returns(ROUTE)
                .example("numen.route.via(\"home\", {at = {x = 100, y = 70, z = -20}})")
                .example("numen.route.via(\"home\", {at = {x = 100, z = -20}, stop = 1})")
                .example("numen.route.via(\"home\", {at = {x = 90, y = 64, z = -10}, arrive = \"near\", near = 2})")
                .example("numen.route.via(\"home\")")
                .note("Instant. The route walks through its stops in order; the leg to each stop keeps its own flags. "
                        + "The old plan is dropped.")
                .seeAlso("route drop", "route show");
        route.server("drop", "Remove a waypoint from a route.", (src, args) -> src.reply(RouteOps.drop(
                        src.companion(), args.get(NAME), args.get(DROPPED))), NAME, DROPPED)
                .returns(ROUTE)
                .example("numen.route.drop(\"home\", {stop = 2})")
                .example("numen.route.drop(\"home\")")
                .note("Instant. The stops after it move down by one; the destination itself stays (numen.route.delete removes "
                        + "the whole route). The old plan is dropped.")
                .seeAlso("route show");
        route.server("spec", "Change the route flags of a whole route or of one leg.", (src, args) -> src.reply(
                        RouteOps.spec(src.companion(), args.get(NAME), args.get(LEG), args)),
                        with(List.of(NAME, LEG), RouteSpecFlags.PARAMS))
                .returns(ROUTE)
                .example("numen.route.spec(\"home\", {alter = \"natural\"})")
                .example("numen.route.spec(\"home\", {leg = 2, avoid = {\"water\"}})")
                .note("Instant. Each flag you write replaces its earlier value, the rest stay; a leg's flags add to "
                        + "the whole route's. The old plan is dropped.")
                .seeAlso("route show", "route plan");
        route.server("plan", "Plan a route from where you stand, without moving: each leg's length, the blocks it "
                        + "would break or place, and the ones needing your owner's consent.",
                        (src, args) -> RouteOps.plan(src, own(src, args.get(OWN))), OWN)
                .returns(RouteText.PLAN_CLASS.type())
                .example("numen.route.plan(\"home\")")
                .example("numen.route.plan()")
                .note("Read-only and does not take the body: it returns when the plan is ready, and fails with kind "
                        + "no_path (the plan in err.data) when a leg can't be walked. The plan stays on the route and is "
                        + "what numen.move.go keeps to: it changes only the cells listed here.")
                .note("Each leg is planned as far as one look reaches; a leg that goes past it says where the known "
                        + "part ends. numen.move.go walks on past it, but changes no cell the plan did not list.")
                .seeAlso("move go", "route show");
        route.server("show", "Show a route: its stops and flags, its latest plan and who walked it.",
                        (src, args) -> src.reply(RouteOps.show(src.companion(), args.get(NAME))), NAME)
                .returns(ScriptType.table(ScriptType.field("route", ROUTE, null),
                        ScriptType.optional("plan", RouteText.PLAN_CLASS.type(), "Its latest plan, when it has one.")))
                .example("numen.route.show(\"home\")")
                .note("Instant and read-only.")
                .seeAlso("route plan", "route list");
        route.server("list", "The routes of your owner, one line each.",
                        (src, args) -> src.reply(RouteOps.list(src.companion(), args)), Listing.PAGE)
                .returns("routes", ScriptType.listOf(ROUTE))
                .example("numen.route.list()")
                .note("Instant and read-only. Your own route goto-<your name> holds the latest walk numen.move.goto_ made.")
                .seeAlso("route show");
        route.server("delete", "Delete a route.", (src, args) -> src.reply(RouteOps.delete(src.companion(),
                        args.get(NAME))), NAME)
                .returns(ScriptType.NOTHING)
                .example("numen.route.delete(\"home\")")
                .note("Instant.")
                .seeAlso("route list");
        route.server("reverse", "Make the route back: the same stops the other way, ending where the route was last "
                        + "planned from.", (src, args) -> src.reply(RouteOps.reverse(src.companion(), args.get(NAME),
                        args.get(AS))), NAME, AS)
                .returns(ROUTE)
                .example("numen.route.reverse(\"mine\")")
                .example("numen.route.reverse(\"mine\", {as = \"mine_home\"})")
                .note("Instant. Each leg's flags go with its stretch; the whole route's flags are copied. The new "
                        + "route has no plan yet.")
                .seeAlso("route plan");
    }
}
