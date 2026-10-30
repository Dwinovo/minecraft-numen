package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.FnArgs;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 脚本函数执行体手里的一次调用:这一次的参数,和几个<b>受权的动作</b>。
 *
 * <p>脚本不是后门:改世界、伤实体的事只能经 {@link #useEntity}、{@link #attack}、{@link #useBlock}、{@link #breakBlock}——先交给权限层,
 * 放行(或主人点了头)才跑你给的那一段 {@code deed},不许就以 {@code denied} 失败、带着理由;这几个动作交回的是一件等着的事,
 * 函数声明 {@code fn.pending()} 并把它返回。{@code deed} 里做了什么、用 {@link #report} 说一句,随结果写进她的回执。
 * 权限层只在执行的那一刻裁决,这里没有"能不能"的判断。
 */
public final class ScriptCall {

    private final String function;
    private final ServerCall call;
    private final FnArgs args;
    private final List<String> said = new ArrayList<>();

    ScriptCall(String function, ServerCall call, FnArgs args) {
        this.function = function;
        this.call = call;
        this.args = args;
    }

    /** 一个必须写的参数的值(收下余下全部的是一串)。 */
    public Object arg(String name) {
        return args.get(name);
    }

    /** 一个可以不写的参数的值;没写是 null。 */
    public Object option(String name) {
        return args.option(name).orElse(null);
    }

    /** 她是谁:她的 UUID。 */
    public String companion() {
        return call.companion().toString();
    }

    /**
     * 点名的那只实体,此刻在她这一层世界里。
     *
     * @param ref 实体的身份,{@code numen.scan.entities} 交回的那种({@code zombie@3f9a1c})
     */
    public Entity entity(String ref) {
        return call.entity(new EntityRef(ref));
    }

    /**
     * 从她的眼睛看向 {@code target} 的朝向(俯仰与偏航,度),给要朝向的模组接口用——{@code facing.pitch()}、{@code facing.yaw()} 是现成的
     * Java 供应器(见 {@link ScriptWeapon.Facing})。只是算,不转她的身体。
     */
    public ScriptWeapon.Facing facingTo(Entity target) {
        net.minecraft.world.phys.Vec3 d = target.getBoundingBox().getCenter().subtract(call.her().getEyePosition());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        return new ScriptWeapon.Facing((float) -Math.toDegrees(Math.atan2(d.y, horizontal)),
                (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
    }

    /** 往这一次调用的回执里说一句:{@code deed} 里身体做了什么、出了什么事。 */
    public void report(String words) {
        said.add(words);
    }

    /** 用一只实体(右键它那样的一件事):够得着、过权限层({@code use_entity}),放行后再认一次、再量一次,才跑 {@code deed}。 */
    public Pending<Object> useEntity(String ref, EntityDeed deed) {
        Entity target = entity(ref);
        call.reach(target, ServerCall.Reach.hand());
        EntityRef same = EntityRef.of(target);
        return gated(Action.useEntity(target), () -> {
            Entity still = call.entity(same);
            call.reach(still, ServerCall.Reach.hand());
            return deed.run(call.her(), still);
        });
    }

    /** 打一只实体:过权限层({@code attack}),放行后再认一次才跑 {@code deed}。不量够不够得着——远程武器隔着距离打。 */
    public Pending<Object> attack(String ref, EntityDeed deed) {
        Entity target = entity(ref);
        EntityRef same = EntityRef.of(target);
        return gated(Action.attack(target), () -> deed.run(call.her(), call.entity(same)));
    }

    /** 用一格方块:过权限层({@code use_block}),放行才跑 {@code deed}。走近是程序的事。 */
    public Pending<Object> useBlock(BlockPos pos, BlockDeed deed) {
        return gated(Action.useBlock(pos, call.her().serverLevel().getBlockState(pos)), () -> deed.run(call.her(), pos));
    }

    /** 挖一格方块:过权限层({@code break}),放行才跑 {@code deed}。 */
    public Pending<Object> breakBlock(BlockPos pos, BlockDeed deed) {
        return gated(Action.breakBlock(pos, call.her().serverLevel().getBlockState(pos)),
                () -> deed.run(call.her(), pos));
    }

    /** 过了权限层才跑 {@code deed};{@link #report} 说过的话和主人点过头的事一起写进回执。 */
    private Pending<Object> gated(Action action, Supplier<Object> deed) {
        return call.authorize(action).thenWait(allowed -> {
            Object result;
            try {
                result = deed.get();
            } catch (RuntimeException error) {
                throw ScriptErrors.asFailure(function, error);
            }
            Pending<Object> done = Pending.create();
            done.report(String.join(" ", said));
            done.complete(result);
            return done;
        });
    }

    /** 跑一只实体上的一段动作:她的身体与那只实体。 */
    @FunctionalInterface
    public interface EntityDeed {
        Object run(NumenPlayer her, Entity target);
    }

    /** 跑一格方块上的一段动作:她的身体与那一格。 */
    @FunctionalInterface
    public interface BlockDeed {
        Object run(NumenPlayer her, BlockPos pos);
    }
}
