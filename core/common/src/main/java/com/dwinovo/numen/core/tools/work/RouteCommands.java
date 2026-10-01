package com.dwinovo.numen.core.tools.work;

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
 * {@code route}:路线这个名词的增删改查与规划。一条路线是意图(途经点、路线标志)加最近一次计划加走过的记录,跟着主人存盘;
 * {@code route plan} 只搜不走、不占身体,{@code move go} 才走,并且只改计划里列出的格。设计稿见 {@code docs/look-plan-act.md}。
 *
 * <p>命令层无状态:每一行都点名路线。去处与到达方式的写法与 {@code move goto} 同一套({@link MoveCommands#ARRIVE}、
 * {@link MoveCommands#NEAR},对应到寻路目标只在 {@link Destination}),路线标志同一串({@link RouteSpecFlags})。
 */
public final class RouteCommands {

    private static final Param<String> NAME = Param.required("name", ArgType.word(), "The route.")
            .values("a route name, as `route list` lists it");
    private static final Param<String> NEW_NAME = Param.required("name", ArgType.word(),
            "Name of the new route: lowercase letters, digits, _ and -.");
    private static final Param<Place> TO = Param.optional("to", ArgType.place(),
            "The destination: coordinates, or an area of your owner's to go into (see --arrive).")
            .whenOmitted("end where you stand now");
    private static final Param<Place> WAYPOINT = Param.optional("at", ArgType.place(),
            "The waypoint: coordinates, or an area of your owner's.")
            .whenOmitted("add where you stand now");
    private static final Param<Integer> AS_STOP = Param.optional("stop", ArgType.integer(1, 99),
            "Which stop it becomes, counting from 1; one past the last makes it the new destination.")
            .whenOmitted("put it just before the destination");
    private static final Param<Integer> DROPPED = Param.optional("stop", ArgType.integer(1, 99),
            "The waypoint's stop number, as `route show` numbers them.")
            .whenOmitted("drop the last waypoint, the one just before the destination");
    private static final Param<Integer> LEG = Param.optional("leg", ArgType.integer(1, 99),
            "Change only this leg, the stretch to stop N.")
            .whenOmitted("change the whole route");
    private static final Param<String> AS = Param.optional("as", ArgType.word(), "Name of the new, reversed route.")
            .whenOmitted("name it after this one with _back, e.g. mine_back");

    private RouteCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(Itinerary.GROUP, "Routes: named walks with waypoints and route flags, planned without "
                + "moving, and kept to when walked with move go.", RouteCommands::actions);
    }

    /** 一个动作的参数表:自己的几个,再接上共用的那几串。 */
    @SafeVarargs
    private static Param<?>[] with(List<? extends Param<?>>... parts) {
        return Stream.of(parts).flatMap(List::stream).toArray(Param<?>[]::new);
    }

    /** 一处写成去处:没写那一处就是她此刻脚下那一格。 */
    private static Destination.Stop stop(ServerSource src, Place written, CommandArgs args) {
        Place place = written != null ? written : Place.cell(Feet.cell(src.companion()));
        return Destination.Stop.of(place, args.get(MoveCommands.ARRIVE), args.get(MoveCommands.NEAR));
    }

    private static void actions(CommandGroup route) {
        route.server("new", "Make a route: from wherever you stand to a destination, with the route flags it walks "
                        + "under.", (src, args) -> src.reply(RouteOps.create(src.companion(), args.get(NEW_NAME),
                        stop(src, args.get(TO), args), args)),
                        with(List.of(NEW_NAME, TO, MoveCommands.ARRIVE, MoveCommands.NEAR), RouteSpecFlags.PARAMS))
                .example("route new home --to 120 64 -35")
                .example("route new back")
                .example("route new mine --to 80 12 -40 --arrive near --near 3 --alter natural")
                .example("route new ore --to ores/g3 --arrive dig --alter natural --avoid-break area:house")
                .note("Instant; it only writes the route down, nothing moves. --to, --arrive and --near mean what the "
                        + "place and flags of move goto mean (numbers are coordinates, a name is an area), and a "
                        + "destination that cannot mean anything here is refused at once the same way. Without --to "
                        + "the route ends where you stand now: a way back here.")
                .note("An area is kept by name: each plan uses the area as it is then, and says so if it is gone.")
                .note("Without route flags the route changes no block. Routes belong to your owner: every companion "
                        + "of theirs sees and walks the same ones, and they survive restarts.")
                .seeAlso("route via", "route plan", "move go");
        route.server("via", "Add a waypoint to a route.", (src, args) -> src.reply(RouteOps.via(src.companion(),
                        args.get(NAME), stop(src, args.get(WAYPOINT), args), args.get(AS_STOP))),
                        NAME, WAYPOINT, MoveCommands.ARRIVE, MoveCommands.NEAR, AS_STOP)
                .example("route via home --at 100 70 -20")
                .example("route via home --at 100 70 -20 --stop 1")
                .example("route via home --at farm --arrive near --near 2")
                .example("route via home")
                .note("Instant. The route walks through its stops in order; the leg to each stop keeps its own flags. "
                        + "The old plan is dropped.")
                .seeAlso("route drop", "route show");
        route.server("drop", "Remove a waypoint from a route.", (src, args) -> src.reply(RouteOps.drop(
                        src.companion(), args.get(NAME), args.get(DROPPED))), NAME, DROPPED)
                .example("route drop home --stop 2")
                .example("route drop home")
                .note("Instant. The stops after it move down by one; the destination itself stays (route delete removes "
                        + "the whole route). The old plan is dropped.")
                .seeAlso("route show");
        route.server("spec", "Change the route flags of a whole route or of one leg.", (src, args) -> src.reply(
                        RouteOps.spec(src.companion(), args.get(NAME), args.get(LEG), args)),
                        with(List.of(NAME, LEG), RouteSpecFlags.PARAMS))
                .example("route spec home --alter natural")
                .example("route spec home --leg 2 --avoid water area:farm")
                .note("Instant. Each flag you write replaces its earlier value, the rest stay; a leg's flags add to "
                        + "the whole route's. The old plan is dropped.")
                .note("Areas in the flags (area:<name>) are kept by name: one that does not exist is refused now, one "
                        + "deleted later is named when the route is planned.")
                .seeAlso("route show", "route plan");
        route.server("plan", "Plan a route from where you stand, without moving: each leg's length, the blocks it "
                        + "would break or place, and the ones needing your owner's consent.",
                        (src, args) -> RouteOps.plan(src, args.get(NAME)), NAME)
                .example("route plan home")
                .note("Read-only and does not take the body: it replies when the plan is ready. The plan stays on "
                        + "the route and is what move go keeps to: it changes only the cells listed here.")
                .note("Each leg is planned as far as one look reaches; a leg that goes past it says where the known "
                        + "part ends. move go walks on past it, but changes no cell the plan did not list.")
                .seeAlso("move go", "route show");
        route.server("show", "Show a route: its stops and flags, its latest plan and who walked it.",
                        (src, args) -> src.reply(RouteOps.show(src.companion(), args.get(NAME))), NAME)
                .example("route show home")
                .note("Instant and read-only.")
                .seeAlso("route plan", "route list");
        route.server("list", "The routes of your owner, one line each.",
                        (src, args) -> src.reply(RouteOps.list(src.companion(), args)), Listing.PAGE)
                .example("route list")
                .note("Instant and read-only. Your own route goto-<your name> holds the latest move goto.")
                .seeAlso("route show");
        route.server("delete", "Delete a route.", (src, args) -> src.reply(RouteOps.delete(src.companion(),
                        args.get(NAME))), NAME)
                .example("route delete home")
                .note("Instant.")
                .seeAlso("route list");
        route.server("reverse", "Make the route back: the same stops the other way, ending where the route was last "
                        + "planned from.", (src, args) -> src.reply(RouteOps.reverse(src.companion(), args.get(NAME),
                        args.get(AS))), NAME, AS)
                .example("route reverse mine")
                .example("route reverse mine --as mine_home")
                .note("Instant. Each leg's flags go with its stretch; the whole route's flags are copied. The new "
                        + "route has no plan yet.")
                .seeAlso("route plan");
    }
}
