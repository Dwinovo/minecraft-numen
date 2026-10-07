package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.core.task.combat.Fought;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * {@code numen.fight}:打。一个函数 {@code attack} 只打点名的那一只,占身体、进任务槽。里面是这件事每刻必需的控制:追着保持在够得着处、
 * 盯着转头、等冷却出手、换远程、躲爆炸;目标死了、丢了、超时就收尾。打哪几只、打完捡掉落都不在里面:那是秒级的决策,归脚本(模块里的
 * {@code numen.fight.clear} 扫一眼敌对的、一只一只打)。
 *
 * <p><b>不问模型用什么武器</b>——那要看走到跟前时还有多远、有没有视线、还剩几支箭,全是模型在派发那一刻看不到的东西。
 */
public final class FightApi {

    /** 打一只给多久。 */
    private static final long TICKS = 120L * 20L;

    private FightApi() {}

    public static void install(NumenApi numen) {
        numen.api("fight", "Combat: attack one entity you name. numen.fight.clear (library) fights off every hostile "
                + "near you, one by one.", FightApi.class);
    }

    /** 打哪一只。 */
    public record Attack(@Doc("The entity to fight: an Entity from numen.scan.entities, or its id.") EntityRef entity) {}

    /**
     * 点名的实体受理这一刻按运行期编号找到;找不到就当场失败。重启后再跑的那一行写成它的 UUID:运行期编号重启后会发给别的东西,照着旧号
     * 再跑可能打到毫不相干的一只。
     */
    @Fn("Attack one entity until it is dead, lost or out of reach.")
    @Example("numen.fight.attack(184)")
    @Example("local r = numen.fight.attack(184)\nprint(r.strikes, #r.fought)")
    @Note("Several: `for _, foe in ipairs(numen.scan.entities(\"hostile\", {radius = 16})) do numen.fight.attack(foe) "
            + "end`; `numen.fight.clear()` (library) does that until none is left.")
    @Note("Background work: it keeps chasing, turning, swinging, shooting and dodging until that one is dead, gone or "
            + "out of reach, and returns then.")
    @Note("The body picks how: it closes in and swings when it can reach, shoots with a bow or crossbow when it "
            + "cannot, keeps its distance from things that explode, and picks the weapon you own that is strongest "
            + "against that target.")
    @Note("It does not pick up what the target drops: `numen.work.collect()` does. Things that split (slimes, magma "
            + "cubes) come back as new ids: scan again, or `numen.fight.clear()`.")
    @Note("Asks your owner before hitting a pet, a named mob or a villager when their rules say so.")
    @SeeAlso({"numen.scan.entities", "numen.fight.clear", "numen.work.collect", "numen.task.stop"})
    public static Job<Fought> attack(ServerCall call, Attack args) {
        Entity target = call.entity(args.entity());
        AttackTaskRecord record = new AttackTaskRecord(call.fn(), call.callId(),
                call.her().level().getGameTime() + TICKS, List.of(target.getId()), false);
        return Job.<Fought>of(record).replayedAs(new Attack(EntityRef.of(target)));
    }
}
