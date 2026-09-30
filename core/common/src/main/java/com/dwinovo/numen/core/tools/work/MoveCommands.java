package com.dwinovo.numen.core.tools.work;

import java.util.List;
import java.util.stream.Stream;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.core.route.Itinerary;
import com.dwinovo.numen.core.route.RouteFlags;
import com.dwinovo.numen.core.route.Routes;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.core.task.move.FollowTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.tools.RouteSpecFlags;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.world.entity.Entity;

/**
 * {@code move}:走一条路线、走到一处(站进去、站上去、去用那一格方块、停在附近)、跟着谁走。
 *
 * <p>三个动作都在服务端,都占身体,交任务槽(受理即回执,收尾走 task_finished;跟随是常驻的活,不收尾)。{@code go} 走一条
 * 路线({@code route} 组里的名词):从她此刻的位置规划,守着那条路线上她看过的计划走。{@code goto} 是它的简写——把这一趟写成
 * 她自己的那条匿名路线({@link Itinerary#gotoOf}),再走它,同一份代码;提升成快捷工具 {@code move_goto},最常用的身体动作。
 * 去处是坐标或主人名下的一块区域({@code --area}):坐标就是只有一格的区域。去处与到达方式怎么对应到寻路的目标、写错了怎么提醒,
 * 只在 {@link Destination};路线标志的翻译只在 {@link RouteSpecFlags}。
 */
public final class MoveCommands {

    static final String GROUP = "move";

    /** follow 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final int DEFAULT_DISTANCE = 3;
    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 16;
    /** 去处的 near 最多放宽几格。 */
    private static final int MAX_NEAR = 16;

    private static final Param<Integer> X = Param.optional("x", ArgType.integer(),
            "Target X. With z alone: that place, at whatever height stands there. With y and z: that one cell.");
    private static final Param<Integer> Y = Param.optional("y", ArgType.integer(),
            "Target height. Leave it out to go to a place (x and z, at whatever height stands there). With x and z: "
                    + "that one cell. Alone: climb or descend to that height.");
    private static final Param<Integer> Z = Param.optional("z", ArgType.integer(), "Target Z; see x.");
    private static final Param<AreaRef> AREA = Param.optional("area", ArgType.area(),
            "Instead of coordinates: an area of your owner's, the whole of it or one part (ores/g3). --arrive counts "
                    + "for any of its cells: at stands in any of them, use uses any of its blocks, near stops within "
                    + "--near of any of them, dig stands within reach of any of its blocks.");
    /** 怎样算到了:goto 与 route 组写去处的地方共用。 */
    static final Param<String> ARRIVE = Param.optional("arrive", ArgType.oneOf(Destination.ARRIVE_WORDS),
            "What counts as there. at: stand in that cell (or column, or height, or any cell of the area). use: stand "
                    + "where that block (or any block of the area) is in sight and in reach, to use it. near: stop "
                    + "within --near blocks of the cell, place or area. dig: stand where your hand reaches that block "
                    + "(or any block of the area), even if something is in the way, to dig it with work dig.")
            .whenOmitted("arrive at");
    static final Param<Integer> NEAR = Param.optional("near", ArgType.integer(1, MAX_NEAR),
            "With --arrive near only: anywhere within this many blocks counts as there.");
    private static final Param<String> ROUTE = Param.required("route", ArgType.word(), "The route to walk.")
            .values("a route name, as `route list` lists it");
    private static final Param<Integer> DISTANCE = Param.optional("distance",
            ArgType.integer(MIN_DISTANCE, MAX_DISTANCE), "How close to stay, in blocks.")
            .whenOmitted("stay within " + DEFAULT_DISTANCE);
    private static final Param<EntityRef> ENTITY_ID = Param.optional("entity_id", ArgType.entity(),
            "Who to follow.")
            .values("a runtime entity id from scan_entities")
            .whenOmitted("follow your owner");

    private MoveCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Getting around: walk a route, go to a place or to where you can use a block, "
                + "follow someone.", MoveCommands::actions);
    }

    private static void actions(CommandGroup move) {
        move.server("goto", "Travel to one destination with full terrain pathfinding.", MoveCommands::goTo,
                        with(List.of(X, Y, Z, AREA, ARRIVE, NEAR), RouteSpecFlags.PARAMS))
                .example("move goto --x 120 --z -35")
                .example("move goto --x 120 --y 64 --z -35 --arrive use")
                .example("move goto --x 120 --y 64 --z -35 --arrive near --near 2")
                .example("move goto --x 120 --y 12 --z -35 --arrive dig --alter natural")
                .example("move goto --y 16 --alter natural")
                .example("move goto --area farm")
                .example("move goto --area storage --arrive use")
                .example("move goto --x 120 --y 12 --z -35 --alter natural --avoid_break minecraft:chest area:house")
                .note("Where: x and z (a place), x, y and z (one cell), y alone (a height), or --area, one of your "
                        + "owner's areas (a coordinate is just an area of one cell). To find a block, scan for it first "
                        + "(`scan blocks`), then give its coordinates.")
                .note("With --area she heads for the side of the area nearest her: at walks into any of its cells she "
                        + "can stand in, use goes to any of its blocks she can use, near stops within --near of any of "
                        + "its cells, dig stops within reach of any of its blocks. To go to another part of it, name "
                        + "that part (ores/g3).")
                .note("--arrive says what counts as there: at (default) stands in the cell or column exactly — to "
                        + "stand on top of a block, give the cell above it; use stands where the block is in sight and "
                        + "in reach, never touching it; near stops within --near blocks; dig stands where the hand "
                        + "reaches the block, which may still be buried — it leaves the block itself for work dig.")
                .note("A call that cannot mean anything here fails at once with the reason and the ways to write it: "
                        + "arrive at into a solid block or mid-air on a walk that changes nothing, arrive use or dig "
                        + "without y, arrive use or dig on air, arrive use on a block walled in on every side, an area "
                        + "that does not exist or has nowhere to stand in, nothing to use or nothing to dig.")
                .note("Shorthand for a route: it writes the walk as your own route goto-<your name>, plans it from "
                        + "where you stand and walks it, exactly as move go does. When it fails, the reply gives the "
                        + "line that changes that route, e.g. route spec with --alter natural, then route plan.")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("Changes nothing in the world unless --alter natural or any, and then only the blocks its plan "
                        + "lists; blocks that are someone's are asked about before setting off.")
                .note("Started while sitting in a boat, she pilots it toward the target; any other vehicle is "
                        + "stepped off.")
                .seeAlso("scan blocks", "move go", "route plan", "task stop")
                .promote("""
                        Travel to ONE destination with full terrain pathfinding: walks, jumps, swims, climbs, opens doors and gates, parkours, and auto-equips tools. It takes coordinates or one of your owner's areas — to find a block, scan_blocks first and give its coordinates.
                        WHERE (fill exactly one pattern):
                        • x+z — a place, at whatever height stands there. The DEFAULT for exploration or "go there"; omit y.
                        • x+y+z — one cell.
                        • y — climb/descend to that height where you are (with alter:'natural' to dig down).
                        • area — an area of your owner's by name, or one part of it ('ores/g3'); a coordinate is just an area of one cell. She heads for the side of it nearest her.
                        ARRIVE — what counts as there (default 'at'):
                        • at — stand IN that cell (or column, or height, or any cell of the area), however the body is held there: standing, on a ladder, in water. To stand on top of a block, give the cell above it.
                        • use — x+y+z of a block you want to use (furnace, chest, crafting table, bed…), or an area to use any one of its blocks: stands where one of its open faces is in sight and in reach, never touching it. Then call use block on it.
                        • near — with near:<n>, anywhere within n blocks of the cell, place or area.
                        • dig — x+y+z of a block to dig (an ore, a buried block), or an area: stands where the hand reaches it, even if it is buried or out of sight; with alter:'natural' she digs and pillars her way there. The block itself is left for work_dig.
                        A call that cannot mean anything here fails at once, saying why and how to write it (e.g. arrive 'at' into a furnace on a walk that changes nothing, or 'use' on a block walled in on every side). It never guesses what you meant.
                        A ROUTE UNDER THE HOOD: every move_goto is written as your own route goto-<your name> (the reply names it), planned from where you stand, then walked — the same as `move go`. The plan is a promise: the walk changes only the blocks it lists, and if the world changes on the way so that more would be needed, it stops and says which. A walk longer than one look is planned as far as it can see and worked out on the way; a walk that changes nothing goes all the way.
                        TERRAIN: the walk never changes the world unless you say so — walls, floors, other people's builds and the landscape stay exactly as they were. When there is no clean route, the call FAILS and says what a route would take (how many blocks) and the exact next line to copy, such as route spec on your route with --alter natural, then route plan to see which blocks before walking. Underground travel and climbing out of pits usually need alter:'natural'. Blocks that are someone's are asked about before setting off. Every call reports what it actually broke or placed.
                        ROUTE FIELDS (all optional): alter 'none'|'natural'|'any' — may the walk dig, bridge, pillar ('any' also through blocks that need the owner's consent, asking first); avoid — cell types to keep out of (water, flowing_water, lava, climbable, door, hazard, falling, trigger, fragile), or areas to stay out of ('area:farm'); allow — cell types kept out by default that this walk may use (flowing_water, trigger, fragile); penalty_place / penalty_break / penalty_jump / penalty_wade — make an action pricier so she detours instead; avoid_break / avoid_place / avoid_step — blocks (ids or #tags), cells ('x,y,z') or areas ('area:house') she must not break, place into, or stand on (a box of cells is an area); parkour — running jumps over gaps; max_fall — highest drop without water; alter_budget — how many blocks the whole route may change; routes over budget are dropped.
                        VEHICLES: start a move_goto while sitting in a boat (see <riding>) and she pilots it over the water toward the target — a destination on the water keeps her aboard, a destination ashore has her step off at the shore and finish on foot. Any other vehicle is stepped off the moment walking begins. Boarding is `use entity` right on the boat.
                        BACKGROUND: a successful call means movement is already running; its end arrives as a matching task_finished. status=done means that destination is complete, so advance the plan and never resend identical coordinates. Only status=timeout permits the same call to resume.""");
        move.server("go", "Walk a route: plan it from where you stand and walk it, keeping to the plan the route "
                        + "has.", MoveCommands::go, ROUTE)
                .example("move go home")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("A route planned before (route plan) is a promise: if planning from where you stand now would "
                        + "break, place or ask about any cell that plan did not, it does not set off and says which. "
                        + "A route never planned is planned and walked straight away, and that plan becomes its promise.")
                .note("Every cell needing your owner's consent is asked about before the first step. On the way it "
                        + "changes only the cells of the plan; when the world changes so that the way on needs more, it "
                        + "stops and says which. A leg the plan saw only part of is walked all the same, worked out on "
                        + "the way; past the part it saw it still changes no cell the plan did not list.")
                .seeAlso("route plan", "route show", "move goto", "task stop");
        move.server("follow", "Tag along with your owner, or with an entity you name, until given something else "
                        + "to do.", MoveCommands::follow, DISTANCE, ENTITY_ID)
                .example("move follow")
                .example("move follow --entity_id 184 --distance 5")
                .note("A standing job: there is nothing to finish, so it never ends on its own and never sends "
                        + "task_finished. She goes quiet while already beside them.")
                .note("Following a named entity ends if it dies or leaves the loaded area; following your owner "
                        + "just waits while they are offline.")
                .note("Never breaks or places a block. When the only way to them needs digging, bridging or "
                        + "pillaring, it ends with a failure saying so; move goto there with --alter natural (ask your "
                        + "owner unless it is obviously natural terrain), then follow again.")
                .seeAlso("scan entities", "move goto", "task stop");
    }

    /** 一个动作的参数表:自己的几个,再接上共用的那几串。 */
    @SafeVarargs
    private static Param<?>[] with(List<? extends Param<?>>... parts) {
        return Stream.of(parts).flatMap(List::stream).toArray(Param<?>[]::new);
    }

    /**
     * 简写:这一行写成她自己的那条匿名路线(去处、到达方式、路线标志原样记下,旧的那一条换掉),再和 {@code move go} 一样走它。
     * 去处按受理这一刻的世界编一遍({@link Destination#of}:写错了当场提醒,不派活、不改路线)。
     */
    private static void goTo(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        NamedAreas areas = NamedAreas.of(her);
        RouteSpec spec = RouteSpecFlags.parse(args, RouteSpec.defaults(), areas);
        Destination.Stop stop = Destination.Stop.of(args.get(X), args.get(Y), args.get(Z), args.get(AREA),
                args.get(ARRIVE), args.get(NEAR));
        Destination.of(her, stop, spec, areas, Feet.cell(her));
        String name = Itinerary.gotoOf(her.getGameProfile().getName());
        Routes.of(her.getServer(), her.getOwnerUuid()).put(Itinerary.of(name, her.level().dimension().location(), stop,
                RouteFlags.written(args)));
        String label = stop.describe() + (spec.alter().mayAlter() ? "(可开路)" : "");
        TaskDispatch.setTask(src, new MoveToTaskRecord(src, name, label, "I keep this walk as my route " + name
                + ": route show " + name + " shows its plan once it is made."));
    }

    /** 走一条路线:路线得在;走的时候从存档里取它,规划、守承诺都在任务里。 */
    private static void go(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        String name = args.get(ROUTE);
        Itinerary route = Routes.of(her.getServer(), her.getOwnerUuid()).get(name);
        if (route == null) {
            throw new IllegalArgumentException("there is no route named " + name + "; route list shows the routes you "
                    + "have, route new makes one");
        }
        TaskDispatch.setTask(src, new MoveToTaskRecord(src, name, "走路线 " + name + ",去 "
                + route.destination().describe(), null));
    }

    /**
     * 跟着走——纯<b>常驻</b>的活:它没有"干完"这回事,只有被主人换掉。所以没有 count、没有期限,派下去之后她就一直
     * 跟着,直到主人让她做别的({@code work mine}、{@code work fish}……都会顶掉它)。
     *
     * <p>不给 {@code entity_id} 就是跟主人,给了就跟那一只——村民、狼、别的玩家都行。两者目标消失时的含义不同,
     * 见 {@code FollowTaskRecord#target}。点名的那只按 UUID 认:记录里存它,重启后重放的那一行也写它
     * ({@link ServerSource#replayedWith}),运行期编号只在受理这一刻用来找到它。
     */
    private static void follow(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        Integer asked = args.get(DISTANCE);
        int distance = asked == null ? DEFAULT_DISTANCE : Math.clamp(asked, MIN_DISTANCE, MAX_DISTANCE);
        EntityRef named = args.get(ENTITY_ID);
        if (named == null) {
            TaskDispatch.setTask(src, new FollowTaskRecord(src, distance, null, null));
            return;
        }
        Entity target = named.in(companion.serverLevel());
        if (target == null || target == companion) {
            src.reply(TaskResult.fail("no entity with id " + named
                    + " is here — scan_entities first, ids do not survive restarts").toJson());
            return;
        }
        EntityRef stable = EntityRef.of(target);
        TaskDispatch.setTask(src.replayedWith(args.with(ENTITY_ID, stable)),
                new FollowTaskRecord(src, distance, target.getUUID(), target.getName().getString()));
    }
}
