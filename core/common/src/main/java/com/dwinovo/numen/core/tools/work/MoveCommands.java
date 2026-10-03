package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.route.Itinerary;
import com.dwinovo.numen.core.route.Routes;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.core.task.move.FollowTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.world.entity.Entity;

/**
 * {@code move}:移动——照一条路线走({@code go}),跟着谁走({@code follow})。两个动作都在服务端,都占身体,交任务槽(受理即回执,
 * 收尾走 task_finished)。
 *
 * <p>{@code go} 只走:路线要先规划过({@code route.plan}),计划就是它守的承诺,从她此刻的位置起只改承诺里的格;没规划过的当场拒绝,
 * 说清先规划。规划与走分开,"去一处"是库里的 {@code move.goto_}:把这一趟写成她自己的路线、规划、再走,三次 API 调用。去处与到达
 * 方式怎么对应到寻路的目标只在 {@link Destination}。
 */
public final class MoveCommands {

    static final String GROUP = "move";

    /** follow 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final int DEFAULT_DISTANCE = 3;
    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 16;
    /** follow 至多跟多久:一个游戏日。 */
    private static final int MAX_FOLLOW_SECONDS = 20 * 60;
    /** 去处的 near 最多放宽几格。 */
    private static final int MAX_NEAR = 16;

    /** 怎样算到了:route 组写去处的地方共用。 */
    static final Param<String> ARRIVE = Param.optional("arrive", ArgType.oneOf(Destination.ARRIVE_WORDS),
            "What counts as there. at: stand in that cell (or column, or height, or any cell of the area). use: stand "
                    + "where that block (or any block of the area) is in sight and in reach, to use it. near: stop "
                    + "within the near option's blocks of the cell, place or area. dig: stand where your hand reaches "
                    + "that block (or, for an area, where it reaches the most of its blocks), even if something is in "
                    + "the way, to dig it with work.dig. reach: stand where your hand reaches that cell, air too, "
                    + "without standing in it, to build into it with build.at.")
            .whenOmitted("arrive at");
    static final Param<Integer> NEAR = Param.optional("near", ArgType.integer(1, MAX_NEAR),
            "With arrive = \"near\" only: anywhere within this many blocks counts as there.")
            .whenOmitted("stop within " + Destination.DEFAULT_NEAR + " blocks");
    private static final Param<String> ROUTE = Param.optionalPositional("route", ArgType.word(), "The route to walk.")
            .values("a route name, as `route.list()` lists it")
            .whenOmitted("walk your own route goto-<your name>, the one route.new writes when given no name");
    private static final Param<Integer> DISTANCE = Param.optional("distance",
            ArgType.integer(MIN_DISTANCE, MAX_DISTANCE), "How close to stay, in blocks.")
            .whenOmitted("stay within " + DEFAULT_DISTANCE);
    private static final Param<Integer> SECONDS = Param.optional("seconds", ArgType.integer(1, MAX_FOLLOW_SECONDS),
            "Follow for this many seconds of world time, then stop and report.")
            .whenOmitted("follow until something else is given to do: no end of its own and no task_finished");
    private static final Param<EntityRef> WHO = Param.optionalPositional("entity", ArgType.entity(),
            "Who to follow.")
            .values("a runtime entity id from scan.entities")
            .whenOmitted("follow your owner");

    private MoveCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Moving: walk a planned route, follow someone. move.goto_ (library) plans and "
                + "walks to one place.", MoveCommands::actions);
    }

    private static void actions(CommandGroup move) {
        move.server("go", "Walk a planned route from where you stand, keeping to its plan.", MoveCommands::go, ROUTE)
                .example("move.go(\"home\")")
                .example("move.go()")
                .note("It only walks: plan the route first with `route.plan`, which lists every block the walk "
                        + "changes and every cell it asks your owner about. That plan is a promise: if the way from "
                        + "where you stand now would break, place or ask about any cell it did not list, it does not "
                        + "set off and says which. A route with no plan is refused with the line that plans it.")
                .note("Background work: it plans the way from where you stand before it replies; an accepted walk's "
                        + "reply carries the plan, the end arrives as a task_finished event. A walk that can't be made "
                        + "is refused with the reason — no task id, no task_finished.")
                .note("Every cell needing your owner's consent is asked about before the first step. On the way it "
                        + "changes only the cells of the plan; when the world changes so that the way on needs more, it "
                        + "stops and says which. A leg the plan saw only part of is walked all the same, worked out on "
                        + "the way; past the part it saw it still changes no cell the plan did not list.")
                .note("Started while sitting in a boat, she pilots it toward the target; any other vehicle is "
                        + "stepped off.")
                .seeAlso("route plan", "route new", "move goto_", "task stop");
        move.server("follow", "Tag along with your owner, or with an entity you name.", MoveCommands::follow, WHO,
                        DISTANCE, SECONDS)
                .example("move.follow()")
                .example("move.follow(184, {distance = 5, seconds = 60})")
                .note("Without seconds it is a standing job: there is nothing to finish, so it ends only when your "
                        + "owner stops it or something else is given to do, and it never sends task_finished. With "
                        + "seconds it stops after that long and reports as a task_finished event, so a script waits for "
                        + "it. She goes quiet while already beside them.")
                .note("Following a named entity ends if it dies or leaves the loaded area; following your owner "
                        + "just waits while they are offline. When they are already out of reach as you call it, the "
                        + "call is refused with the reason and nothing starts.")
                .note("Never breaks or places a block. When the only way to them needs digging, bridging or "
                        + "pillaring, it ends with a failure saying so.")
                .seeAlso("scan entities", "move goto_", "task stop");
    }

    /** 走一条路线:路线得在;走的时候从存档里取它,守承诺、没规划过当场拒绝都在任务里。 */
    private static void go(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        String name = args.get(ROUTE) != null ? args.get(ROUTE) : Itinerary.gotoOf(her.getGameProfile().getName());
        Itinerary route = Routes.of(her.getServer(), her.getOwnerUuid()).get(name);
        if (route == null) {
            throw new IllegalArgumentException("there is no route named " + name + "; `route.list()` shows the "
                    + "routes you have, `route.new` makes one");
        }
        TaskDispatch.setTask(src.replayedWith(args.with(ROUTE, name)), new MoveToTaskRecord(src, name,
                "走路线 " + name + ",去 " + route.destination().describe(), null));
    }

    /**
     * 跟着走:不点名就是跟主人,点名了就跟那一只——两者目标消失时的含义不同,见 {@code FollowTaskRecord#target}。点名的那只按
     * UUID 认:记录里存它,重启后重放的那一行也写它({@link ServerSource#replayedWith}),运行期编号只在受理这一刻用来找到它。
     * 不给秒数是常驻的活,没有期限;给了就是一件到时收尾的活。
     */
    private static void follow(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        Integer asked = args.get(DISTANCE);
        int distance = asked == null ? DEFAULT_DISTANCE : Math.clamp(asked, MIN_DISTANCE, MAX_DISTANCE);
        Integer seconds = args.get(SECONDS);
        long ticks = seconds == null ? 0 : Math.clamp(seconds, 1, MAX_FOLLOW_SECONDS) * 20L;
        EntityRef named = args.get(WHO);
        if (named == null) {
            TaskDispatch.setTask(src, new FollowTaskRecord(src, distance, null, null, ticks));
            return;
        }
        Entity target = named.in(companion.serverLevel());
        if (target == null || target == companion) {
            src.reply(TaskResult.fail("no entity with id " + named
                    + " is here — `scan.entities()` first, ids do not survive restarts").toJson());
            return;
        }
        EntityRef stable = EntityRef.of(target);
        TaskDispatch.setTask(src.replayedWith(args.with(WHO, stable)),
                new FollowTaskRecord(src, distance, target.getUUID(), target.getName().getString(), ticks));
    }
}
