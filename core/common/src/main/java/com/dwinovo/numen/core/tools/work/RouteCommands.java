package com.dwinovo.numen.core.tools.work;

import java.util.List;
import java.util.stream.Stream;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
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
    private static final Param<List<Integer>> TO = Param.optional("to", ArgType.list(ArgType.integer()),
            "The destination: x y z (one cell), x z (a place, at whatever height stands there) or y (a height).");
    private static final Param<Integer> X = Param.required("x", ArgType.integer(), "X of the waypoint.");
    private static final Param<Integer> Y = Param.required("y", ArgType.integer(), "Y of the waypoint: that one cell.");
    private static final Param<Integer> Z = Param.required("z", ArgType.integer(), "Z of the waypoint.");
    private static final Param<Integer> AT = Param.optional("at", ArgType.integer(1, 99),
            "Which stop it becomes, counting from 1; one past the last makes it the new destination.")
            .whenOmitted("just before the destination");
    private static final Param<String> WHAT = Param.required("what", ArgType.oneOf("via"),
            "What to drop: via, one waypoint.");
    private static final Param<Integer> STOP = Param.required("stop", ArgType.integer(1, 99),
            "The stop number, as `route show` numbers them.");
    private static final Param<Integer> LEG = Param.optional("leg", ArgType.integer(1, 99),
            "Change only this leg, the stretch to stop N.")
            .whenOmitted("change the whole route");
    private static final Param<String> AS = Param.optional("as", ArgType.word(), "Name of the new, reversed route.");

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

    private static void actions(CommandGroup route) {
        route.server("new", "Make a route: from wherever you stand to a destination, with the route flags it walks "
                        + "under.", (src, args) -> {
                    if (args.get(TO) == null) {
                        throw new IllegalArgumentException("route new needs --to: x y z (one cell), x z (a place) or y "
                                + "(a height)");
                    }
                    src.reply(RouteOps.create(src.companion(), args.get(NEW_NAME), RouteOps.stop(args.get(TO),
                            args.get(MoveCommands.ARRIVE), args.get(MoveCommands.NEAR)), args));
                }, with(List.of(NEW_NAME, TO, MoveCommands.ARRIVE, MoveCommands.NEAR), RouteSpecFlags.PARAMS))
                .example("route new home --to 120 64 -35")
                .example("route new mine --to 80 12 -40 --arrive near --near 3 --alter natural")
                .note("Instant; it only writes the route down, nothing moves. --to, --arrive and --near mean what the "
                        + "same fields of move goto mean, and a destination that cannot mean anything here is refused "
                        + "at once the same way.")
                .note("Without route flags the route changes no block. Routes belong to your owner: every companion "
                        + "of theirs sees and walks the same ones, and they survive restarts.")
                .seeAlso("route via", "route plan", "move go");
        route.server("via", "Add a waypoint to a route.", (src, args) -> src.reply(RouteOps.via(src.companion(),
                        args.get(NAME), Destination.Stop.of(args.get(X), args.get(Y), args.get(Z),
                                args.get(MoveCommands.ARRIVE), args.get(MoveCommands.NEAR)), args.get(AT))),
                        NAME, X, Y, Z, MoveCommands.ARRIVE, MoveCommands.NEAR, AT)
                .example("route via home 100 70 -20")
                .example("route via home 100 70 -20 --at 1")
                .note("Instant. The route walks through its stops in order; the leg to each stop keeps its own flags. "
                        + "The old plan is dropped.")
                .seeAlso("route drop", "route show");
        route.server("drop", "Remove a waypoint from a route.", (src, args) -> src.reply(RouteOps.drop(
                        src.companion(), args.get(NAME), args.get(STOP))), NAME, WHAT, STOP)
                .example("route drop home via 2")
                .note("Instant. The stops after it move down by one; the destination itself stays (route delete removes "
                        + "the whole route). The old plan is dropped.")
                .seeAlso("route show");
        route.server("spec", "Change the route flags of a whole route or of one leg.", (src, args) -> src.reply(
                        RouteOps.spec(src.companion(), args.get(NAME), args.get(LEG), args)),
                        with(List.of(NAME, LEG), RouteSpecFlags.PARAMS))
                .example("route spec home --alter natural")
                .example("route spec home --leg 2 --avoid water")
                .note("Instant. Each flag you write replaces its earlier value, the rest stay; a leg's flags add to "
                        + "the whole route's. The old plan is dropped.")
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
                .example("route reverse mine --as mine_back")
                .note("Instant. Each leg's flags go with its stretch; the whole route's flags are copied. The new "
                        + "route has no plan yet.")
                .seeAlso("route plan");
    }
}
