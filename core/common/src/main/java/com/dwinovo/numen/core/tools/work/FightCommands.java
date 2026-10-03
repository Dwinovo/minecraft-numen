package com.dwinovo.numen.core.tools.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.CombatOps;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.world.entity.Entity;

/**
 * {@code fight}:打。一个动作 {@code attack} 只打点名的那一只,占身体、交任务槽。里面是这件事每刻必需的控制:追着保持在够得着处、
 * 盯着转头、等冷却出手、换远程、躲爆炸;目标死了、丢了、超时就收尾。打哪几只、打完捡掉落都不在里面:那是秒级的决策,归脚本
 * (库里的 {@code numen.fight.clear} 扫一眼敌对的、一只一只打)。
 *
 * <p><b>不问模型用什么武器</b>——那要看走到跟前时还有多远、有没有视线、还剩几支箭,全是模型在派发那一刻看不到的东西。
 */
public final class FightCommands {

    static final String GROUP = "fight";

    private static final Param<EntityRef> ENTITY = Param.required("entity", ArgType.entity(), "The entity to fight.")
            .values("an Entity from numen.scan.entities, or its id");

    private FightCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Combat: attack one entity you name. numen.fight.clear (library) fights off every "
                + "hostile near you, one by one.", FightCommands::actions);
    }

    private static void actions(CommandGroup fight) {
        fight.server("attack", "Attack one entity until it is dead, lost or out of reach.",
                        FightCommands::attack, ENTITY)
                .returns(ScriptType.table(
                        ScriptType.field("fought", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("id", ScriptType.INTEGER, null),
                                ScriptType.field("status", ScriptType.STRING, "defeated, lost, unreachable, refused …"),
                                ScriptType.field("strikes", ScriptType.INTEGER, null))),
                                "Each entity it fought, the one you named first."),
                        ScriptType.field("strikes", ScriptType.INTEGER, "Hits in all.")))
                .example("numen.fight.attack(184)")
                .example("local r = numen.fight.attack(184)\nprint(r.strikes, #r.fought)")
                .note("Several: `for _, foe in ipairs(numen.scan.entities(\"hostile\", {radius = 16})) do "
                        + "numen.fight.attack(foe) end`; `numen.fight.clear()` (library) does that until none is left.")
                .note("Background work: it keeps chasing, turning, swinging, shooting and dodging until that one is "
                        + "dead, gone or out of reach, and returns then.")
                .note("The body picks how: it closes in and swings when it can reach, shoots with a bow or "
                        + "crossbow when it cannot, keeps its distance from things that explode, and picks the "
                        + "weapon you own that is strongest against that target.")
                .note("It does not pick up what the target drops: `numen.work.collect()` does. Things that split (slimes, "
                        + "magma cubes) come back as new ids: scan again, or `numen.fight.clear()`.")
                .note("Asks your owner before hitting a pet, a named mob or a villager when their rules say so.")
                .seeAlso("scan entities", "fight clear", "work collect", "task stop");
    }

    /**
     * 点名的实体受理这一刻按运行期编号找到;找不到就当场失败。重启后重放的那一行写成它的 UUID({@link ServerSource#replayedWith}):
     * 运行期编号重启后会发给别的东西,照着旧号重放可能打到毫不相干的一只。
     */
    private static void attack(ServerSource src, CommandArgs args) {
        EntityRef named = args.get(ENTITY);
        Entity e = named.in(src.companion().serverLevel());
        if (e == null || e == src.companion()) {
            src.reply(TaskResult.fail(ErrorKind.NOT_FOUND, "no entity with id " + named + " is here — ids do not "
                    + "survive restarts", "numen.scan.entities()").toJson());
            return;
        }
        TaskDispatch.setTask(src.replayedWith(args.with(ENTITY, EntityRef.of(e))),
                new CombatOps().attack(src, e.getId()));
    }
}
