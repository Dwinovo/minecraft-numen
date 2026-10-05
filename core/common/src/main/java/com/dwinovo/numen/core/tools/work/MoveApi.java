package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.Plans;
import com.dwinovo.numen.core.task.move.FollowCompanionTask;
import com.dwinovo.numen.core.task.move.FollowTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToCompanionTask;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Positional;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * {@code numen.move}:移动——照一份计划走({@code go}),跟着谁走一阵({@code follow}),从坐着的东西上下来({@code dismount})。
 *
 * <p>{@code go} 只走:计划由 {@code numen.route.plan} 算({@link com.dwinovo.numen.core.route.Planning}),计划就是它守的承诺,从她此刻
 * 的位置起只改承诺里的格;要问主人的格走到那一格才问。"去一处"是库里的 {@code numen.move.to}:规划、再走,两次 API 调用。驾船也是
 * 计划里写的移动方式({@code mode = "boat"}),{@code go} 不替她决定要不要驾船。
 */
public final class MoveApi {

    /** follow 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final int DEFAULT_DISTANCE = 3;
    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 16;
    /** follow 默认跟多久:一分钟,够走一段路,又不至于让程序一直等着。 */
    private static final int DEFAULT_FOLLOW_SECONDS = 60;
    /** follow 至多跟多久:一个游戏日。 */
    private static final int MAX_FOLLOW_SECONDS = 20 * 60;

    private MoveApi() {}

    public static void install(NumenApi numen) {
        numen.api("move", "Moving: walk a plan from numen.route.plan, follow someone for a while, step off what you "
                + "ride. numen.move.to (library) plans and walks to one place.", MoveApi.class);
    }

    /** 照哪一份计划走。 */
    public record Go(@Doc("The plan to walk, as numen.route.plan returned it.") Plans.Ref plan) {}

    /** 照一份计划走:计划得是这一段程序里算的,而且走得通;守承诺、半路问主人都在任务里。 */
    @Fn("Walk a plan from numen.route.plan, keeping to it.")
    @Example("local plan = numen.route.plan({to = {x = 120, y = 64, z = -35}})\nnumen.move.go(plan)")
    @Note("It only walks the plan: the plan lists every block the walk changes and every cell it asks your owner "
            + "about, and is a promise — if the way from where you stand now would break, place or ask about any cell "
            + "it did not list, it does not set off and says which. A plan that can't be walked (ok = false) fails "
            + "with kind no_path and its why; a plan from an earlier program is gone (not_found): plan again.")
    @Note("Background work: it returns when the walk ends, with where you stand. Each cell needing your owner's "
            + "consent is asked about when you get to it: yes, and the walk goes on; no, and it stops there with kind "
            + "denied — plan again around that cell (avoid it, or costs.consent = false).")
    @Note("On the way it changes only the cells of the plan; when the world changes so that the way on needs more, it "
            + "stops and says which. A leg the plan saw only in part is worked out on the way. A through stop is "
            + "passed without stopping.")
    @Note("A plan with mode = \"boat\" steers the boat you sit in; a walking plan started in a boat or on a mount "
            + "steps off first (and says so).")
    @SeeAlso({"numen.route.plan", "numen.move.to", "numen.move.dismount", "numen.task.stop"})
    public static Job<MoveToCompanionTask.Walked> go(ServerCall call, Go args) {
        String id = args.plan().id();
        Plan plan = Plans.of(call.her()).get(Plans.program(call.callId()), id);
        if (plan == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "plan " + id + " is not one of this program's: a plan is good "
                    + "only within the program that made it, so plan the walk again",
                    "numen.move.go(numen.route.plan(spec))");
        }
        if (!plan.ok()) {
            throw new ApiError(ErrorKind.NO_PATH, "plan " + id + " can't be walked: " + plan.why(),
                    "numen.route.plan(...) again with the description changed where that reason points");
        }
        return Job.of(new MoveToTaskRecord(call, plan));
    }

    /** 跟谁、多近、多久。 */
    public record Follow(@Doc("Who to follow: an Entity from numen.scan.entities, or its id.")
                         @Omitted("follow your owner") @Positional Optional<EntityRef> entity,
                         @Doc("How close to stay, in blocks (" + MIN_DISTANCE + "-" + MAX_DISTANCE + ").")
                         @Omitted("stay within " + DEFAULT_DISTANCE) Optional<Integer> distance,
                         @Doc("Follow for this many seconds of world time (up to " + MAX_FOLLOW_SECONDS + "), then "
                                 + "stop and return.") @Omitted("follow for " + DEFAULT_FOLLOW_SECONDS + " s")
                         Optional<Integer> seconds) {}

    /**
     * 跟着走:不点名就是跟主人,点名了就跟那一只——两者目标消失时的含义不同,见 {@code FollowTaskRecord#target}。点名的那只按 UUID 认:
     * 记录里存它,重启后再跑的那一行也写它,运行期编号只在受理这一刻用来找到它。总有期限:不给秒数就跟一分钟。
     */
    @Fn("Tag along with your owner, or with an entity you name, for a while.")
    @Example("numen.move.follow()")
    @Example("numen.move.follow(184, {distance = 5, seconds = 30})")
    @Note("Background work with an end: it returns after seconds (default " + DEFAULT_FOLLOW_SECONDS + "), or when "
            + "your owner stops it. You go quiet while already beside them.")
    @Note("Following a named entity ends if it dies or leaves the loaded area; following your owner just waits while "
            + "they are offline. When they are already out of reach as you call it, the call is refused with the "
            + "reason and nothing starts.")
    @Note("Never breaks or places a block. When the only way to them needs digging, bridging or pillaring, it ends "
            + "with a failure saying so.")
    @SeeAlso({"numen.scan.entities", "numen.move.to", "numen.task.stop"})
    public static Job<FollowCompanionTask.Followed> follow(ServerCall call, Follow args) {
        int distance = Math.clamp(args.distance().orElse(DEFAULT_DISTANCE), MIN_DISTANCE, MAX_DISTANCE);
        long ticks = Math.clamp(args.seconds().orElse(DEFAULT_FOLLOW_SECONDS), 1, MAX_FOLLOW_SECONDS) * 20L;
        if (args.entity().isEmpty()) {
            return Job.of(new FollowTaskRecord(call, distance, null, null, ticks));
        }
        Entity target = call.entity(args.entity().get());
        return Job.<FollowCompanionTask.Followed>of(
                        new FollowTaskRecord(call, distance, target.getUUID(), target.getName().getString(), ticks))
                .replayedAs(new Follow(Optional.of(EntityRef.of(target)), args.distance(), args.seconds()));
    }

    /** {@code numen.move.dismount} 交回的值。 */
    @Doc("What you stepped off.")
    public record Dismounted(@Doc("What you stepped off, minecraft:oak_boat.") String vehicle,
                             @Doc("Where you stand now.") Vec3 pos) {}

    /**
     * 下来:原版玩家按潜行就从坐着的东西上下来(服务端在骑乘刻里调 {@code stopRiding}),落脚点由原版的下车位置定。这里直接调同一个
     * {@code stopRiding},结果一样,当场就有;下来了什么、站在哪照实交回。
     */
    @Fn("Step off the boat, minecart or mount you ride, as a player does with sneak.")
    @Example("numen.move.dismount()")
    @Note("Instant. Riding nothing fails with kind failed. Vanilla puts you down beside it, where there is room.")
    @SeeAlso("numen.move.go")
    public static Dismounted dismount(ServerCall call) {
        NumenPlayer her = call.her();
        Entity vehicle = her.getVehicle();
        if (vehicle == null) {
            throw new ApiError(ErrorKind.FAILED, "I am not riding anything", null);
        }
        String what = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
        her.stopRiding();
        return new Dismounted(what, her.position());
    }
}
