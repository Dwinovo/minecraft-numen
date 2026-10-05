package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 服务端的一次调用:这具身体、调的是哪个函数、这次调用写成的那一行 Lua,以及几个原语——认出点名的实体、量够不够得着、过权限层、
 * 等一件短的身体活。改世界、动别人的东西一律经这里的原语,由权限层在执行的那一刻裁决;函数自己不写"能不能"。
 */
public final class ServerCall {

    /** 够不够得着:她此刻站的地方够不够得着那只实体。 */
    @FunctionalInterface
    public interface Reach {

        boolean reaches(NumenPlayer her, Entity target);

        /** 她伸手够得着、看得见:和玩家能交互的距离一样。 */
        static Reach hand() {
            return (her, target) -> her.canInteractWithEntity(target, 0.0);
        }

        /** 比伸手多 {@code extra} 格:界面开着时能离多远这类。 */
        static Reach hand(double extra) {
            return (her, target) -> her.canInteractWithEntity(target, extra);
        }
    }

    private final NumenPlayer her;
    private final ApiFunction function;
    private final Record args;
    private final String callId;

    ServerCall(NumenPlayer her, ApiFunction function, Record args, String callId) {
        this.her = her;
        this.function = function;
        this.args = args;
        this.callId = callId;
    }

    /** 这具身体。 */
    public NumenPlayer her() {
        return her;
    }

    /** 这次调用的 id:派下的活要跟着它。 */
    public String callId() {
        return callId;
    }

    /** 调的函数的全名:{@code numen.work.dig};派下的活、回执都这样叫它。 */
    public String fn() {
        return function.fullName();
    }

    /** 这次调用写成的那一行 Lua:征询里点名这件事、重启后再跑都是它。 */
    public String lua() {
        return Call.of(function, args);
    }

    /**
     * 一件事先过权限层,在主线程对活世界裁决:放行就有值;不许是 {@link ErrorKind#DENIED},说是谁的规矩、为什么;要问就挂着这次调用等
     * 主人答复(不占任务槽),答应了有值,拒绝、超时、被叫停如实失败。
     *
     * @param action 要做的事,如 {@code Action.useEntity(maid)}、{@code Action.command(…)}
     */
    public Pending<Void> authorize(Action action) {
        Gate gate = Permission.gateFor(her);
        Verdict verdict = gate.judgeLive(action, her.serverLevel());
        return switch (verdict.kind()) {
            case ALLOW -> Pending.of(null);
            case DENY -> Pending.failed(refused(lua(), verdict.reason()));
            case ASK -> Consents.of(her).await(lua(),
                    List.of(gate.consentItemLive(action, verdict, her.serverLevel())));
        };
    }

    /** 没做这件事的失败:{@code what} 是要做的事,理由是规则、模式或主人的原话。 */
    static ApiError refused(String what, String why) {
        return new ApiError(ErrorKind.DENIED, "did not run " + what + ": " + why, null);
    }

    /**
     * 点名的那只实体,此刻在这一层世界里。
     *
     * @throws ApiError {@link ErrorKind#NOT_FOUND}:不在了(编号不跨重启)
     */
    public Entity entity(EntityRef ref) {
        Entity e = ref.in(her.serverLevel());
        if (e == null || e == her) {
            throw new ApiError(ErrorKind.NOT_FOUND, "no entity " + ref + " is here — ids do not survive restarts",
                    Call.of("numen.scan.entities"));
        }
        return e;
    }

    /**
     * 她此刻够得着那只实体;够不着是 {@link ErrorKind#OUT_OF_REACH},说多远,下一步是走到它跟前的那一次调用,数据里是它在哪。函数不走路:
     * 走过去是程序的事。
     */
    public void reach(Entity target, Reach reach) {
        if (!reach.reaches(her, target)) {
            throw new ApiError(ErrorKind.OUT_OF_REACH, target.getName().getString() + " is "
                    + String.format(java.util.Locale.ROOT, "%.1f", her.distanceTo(target))
                    + " blocks away — out of reach from where you stand; walk to it first, then call this again",
                    Call.of("numen.move.to", target.blockPosition(), Map.of("arrive", "near", "range", 2)),
                    Map.of("pos", target.blockPosition()));
        }
    }

    /**
     * 用一只实体(右键它那样的一件事):够不够得着、过权限层({@code use_entity}),放行之后按同一个编号再认一次、再量一次——主人答复
     * 回来的时候它可能走开了——再做 {@code deed}。{@code deed} 抛的 {@link ApiError} 是这次调用的失败。
     */
    public <R> Pending<R> use(Entity target, Reach reach, Function<Entity, R> deed) {
        reach(target, reach);
        EntityRef same = EntityRef.id(target.getId());
        return authorize(Action.useEntity(target)).then(allowed -> {
            Entity still = entity(same);
            reach(still, reach);
            return deed.apply(still);
        });
    }

    /**
     * 一件有界的短身体活(几秒内保证做完):这次调用等它做完,不进任务槽、不顶掉手上的活,排在手上那件之上。活交回的值必须是 {@code R};
     * 失败是它说的那一种。
     */
    public <R> Pending<R> sync(TaskRecord record) {
        Pending<R> out = Pending.create();
        TaskDispatch.runSync(her, record, result -> settle(out, result));
        return out;
    }

    @SuppressWarnings("unchecked")
    static <R> void settle(Pending<R> out, TaskResult result) {
        if (result.success()) {
            out.report(result.message());
            out.complete((R) result.value());
        } else {
            out.fail(new ApiError(result.kind(), result.message(), result.hint(), result.value()));
        }
    }

    /**
     * 以服务器的权威、只对她执行原版与模组指令的那条路:包装模组管理指令的函数用(那些指令能作用于任何人,她自己又没有那个权限等级)。
     * 作用对象写死为她,够不着别人。
     */
    public OnHer onHer() {
        return new OnHer(her);
    }
}
