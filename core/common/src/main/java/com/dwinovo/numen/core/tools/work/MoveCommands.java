package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.Plans;
import com.dwinovo.numen.core.route.RouteText;
import com.dwinovo.numen.core.task.move.FollowTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

/**
 * {@code move}:移动——照一份计划走({@code go}),跟着谁走一阵({@code follow}),从坐着的东西上下来({@code dismount})。
 *
 * <p>{@code go} 只走:计划由 {@code numen.route.plan} 算({@link com.dwinovo.numen.core.route.Planning}),计划就是它守的承诺,从她此刻的位置
 * 起只改承诺里的格;要问主人的格走到那一格才问。"去一处"是库里的 {@code numen.move.to}:规划、再走,两次 API 调用。驾船也是计划里写的
 * 移动方式({@code mode = "boat"}),{@code go} 不替她决定要不要驾船。
 */
public final class MoveCommands {

    static final String GROUP = "move";

    /** follow 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final int DEFAULT_DISTANCE = 3;
    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 16;
    /** follow 默认跟多久:一分钟,够走一段路,又不至于让程序一直等着。 */
    private static final int DEFAULT_FOLLOW_SECONDS = 60;
    /** follow 至多跟多久:一个游戏日。 */
    private static final int MAX_FOLLOW_SECONDS = 20 * 60;

    private static final Param<String> PLAN = Param.required("plan", ArgType.table("plan", RouteText.PLAN.type(),
                    MoveCommands::planId, MoveCommands::planJson), "The plan to walk, as numen.route.plan returned it.");
    private static final Param<Integer> DISTANCE = Param.optional("distance",
            ArgType.integer(MIN_DISTANCE, MAX_DISTANCE), "How close to stay, in blocks.")
            .whenOmitted("stay within " + DEFAULT_DISTANCE);
    private static final Param<Integer> SECONDS = Param.optional("seconds", ArgType.integer(1, MAX_FOLLOW_SECONDS),
            "Follow for this many seconds of world time, then stop and return.")
            .whenOmitted("follow for " + DEFAULT_FOLLOW_SECONDS + " s");
    private static final Param<EntityRef> WHO = Param.optionalPositional("entity", ArgType.entity(),
            "Who to follow.")
            .values("a runtime entity id from numen.scan.entities")
            .whenOmitted("follow your owner");

    /** 走完时的结果:她在哪、离终点还有多远。 */
    static final ScriptType MOVED = ScriptType.table(
            ScriptType.field("pos", Shapes.POS.type(), "Where you stand now (decimals)."),
            ScriptType.field("distance_left", ScriptType.NUMBER, "Blocks from the destination; 0 or so when there."));

    private MoveCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Moving: walk a plan from numen.route.plan, follow someone for a while, step off what "
                + "you ride. numen.move.to (library) plans and walks to one place.", MoveCommands::actions);
    }

    private static void actions(CommandGroup move) {
        move.server("go", "Walk a plan from numen.route.plan, keeping to it.", MoveCommands::go, PLAN)
                .returns(MOVED)
                .example("local plan = {id = \"p1\"} -- as numen.route.plan returned it\nnumen.move.go(plan)")
                .note("It only walks the plan: the plan lists every block the walk changes and every cell it asks "
                        + "your owner about, and is a promise — if the way from where you stand now would break, place "
                        + "or ask about any cell it did not list, it does not set off and says which. A plan that "
                        + "can't be walked (ok = false) fails with kind no_path and its why; a plan from an earlier "
                        + "program is gone (not_found): plan again.")
                .note("Background work: it returns when the walk ends, with where you stand. Each cell needing your "
                        + "owner's consent is asked about when you get to it: yes, and the walk goes on; no, and it "
                        + "stops there with kind denied — plan again around that cell (avoid it, or costs.consent = "
                        + "false).")
                .note("On the way it changes only the cells of the plan; when the world changes so that the way on "
                        + "needs more, it stops and says which. A leg the plan saw only in part is worked out on the "
                        + "way. A through stop is passed without stopping.")
                .note("A plan with mode = \"boat\" steers the boat you sit in; a walking plan started in a boat or "
                        + "on a mount steps off first (and says so).")
                .seeAlso("route plan", "move to", "move dismount", "task stop");
        move.server("follow", "Tag along with your owner, or with an entity you name, for a while.",
                        MoveCommands::follow, WHO, DISTANCE, SECONDS)
                .returns(ScriptType.table(ScriptType.field("pos", Shapes.POS.type(), "Where you stand at the end.")))
                .example("numen.move.follow()")
                .example("numen.move.follow(184, {distance = 5, seconds = 30})")
                .note("Background work with an end: it returns after seconds (default " + DEFAULT_FOLLOW_SECONDS
                        + "), or when your owner stops it. She goes quiet while already beside them.")
                .note("Following a named entity ends if it dies or leaves the loaded area; following your owner "
                        + "just waits while they are offline. When they are already out of reach as you call it, the "
                        + "call is refused with the reason and nothing starts.")
                .note("Never breaks or places a block. When the only way to them needs digging, bridging or "
                        + "pillaring, it ends with a failure saying so.")
                .seeAlso("scan entities", "move to", "task stop");
        move.server("dismount", "Step off the boat, minecart or mount you ride, as a player does with sneak.",
                        MoveCommands::dismount)
                .returns(ScriptType.table(
                        ScriptType.field("vehicle", ScriptType.STRING, "What you stepped off, minecraft:oak_boat."),
                        ScriptType.field("pos", Shapes.POS.type(), "Where you stand now.")))
                .example("numen.move.dismount()")
                .note("Instant. Riding nothing fails with kind failed. Vanilla puts you down beside it, where there "
                        + "is room.")
                .seeAlso("move go");
    }

    /** 计划的那张表里认出它是哪一份。 */
    private static String planId(JsonElement value) {
        if (value != null && value.isJsonObject() && value.getAsJsonObject().get("id") instanceof JsonElement id
                && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
            return id.getAsString();
        }
        throw new IllegalArgumentException("expected the Plan numen.route.plan returned (it has an id); got "
                + (value == null ? "nothing" : value.toString()));
    }

    private static JsonElement planJson(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        return o;
    }

    /** 照一份计划走:计划得是这一段程序里算的,而且走得通;守承诺、半路问主人都在任务里。 */
    private static void go(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        String id = args.get(PLAN);
        Plan plan = Plans.of(her).get(Plans.program(src.toolCallId()), id);
        if (plan == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "plan " + id + " is not one of this program's: a plan is good "
                    + "only within the program that made it, so plan the walk again", "numen.move.go(numen.route.plan(spec))");
        }
        if (!plan.ok()) {
            throw new ApiError(ErrorKind.NO_PATH, "plan " + id + " can't be walked: " + plan.why(),
                    "numen.route.plan(...) again with the description changed where that reason points");
        }
        TaskDispatch.setTask(src, new MoveToTaskRecord(src, plan));
    }

    /**
     * 跟着走:不点名就是跟主人,点名了就跟那一只——两者目标消失时的含义不同,见 {@code FollowTaskRecord#target}。点名的那只按
     * UUID 认:记录里存它,重启后重放的那一行也写它({@link ServerSource#replayedWith}),运行期编号只在受理这一刻用来找到它。
     * 总有期限:不给秒数就跟一分钟。
     */
    private static void follow(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        Integer asked = args.get(DISTANCE);
        int distance = asked == null ? DEFAULT_DISTANCE : Math.clamp(asked, MIN_DISTANCE, MAX_DISTANCE);
        Integer seconds = args.get(SECONDS);
        long ticks = Math.clamp(seconds == null ? DEFAULT_FOLLOW_SECONDS : seconds, 1, MAX_FOLLOW_SECONDS) * 20L;
        EntityRef named = args.get(WHO);
        if (named == null) {
            TaskDispatch.setTask(src, new FollowTaskRecord(src, distance, null, null, ticks));
            return;
        }
        Entity target = named.in(companion.serverLevel());
        if (target == null || target == companion) {
            src.reply(TaskResult.fail(ErrorKind.NOT_FOUND, "no entity with id " + named
                    + " is here — ids do not survive restarts", "numen.scan.entities()").toJson());
            return;
        }
        EntityRef stable = EntityRef.of(target);
        TaskDispatch.setTask(src.replayedWith(args.with(WHO, stable)),
                new FollowTaskRecord(src, distance, target.getUUID(), target.getName().getString(), ticks));
    }

    /**
     * 下来:原版玩家按潜行就从坐着的东西上下来(服务端在骑乘刻里调 {@code stopRiding}),落脚点由原版的下车位置定。这里直接调同一个
     * {@code stopRiding},结果一样,当场就有;下来了什么、站在哪照实交回。
     */
    private static void dismount(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        Entity vehicle = her.getVehicle();
        if (vehicle == null) {
            throw new ApiError(ErrorKind.FAILED, "I am not riding anything", null);
        }
        String what = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
        her.stopRiding();
        JsonObject data = new JsonObject();
        data.addProperty("vehicle", what);
        data.add("pos", Shapes.pos(her.position()));
        src.reply(TaskResult.ok("stepped off the " + vehicle.getType().getDescription().getString().toLowerCase(
                java.util.Locale.ROOT) + ", now at " + com.dwinovo.numen.permission.Listing.coords(her.blockPosition())
                + ".", data).toJson());
    }
}
