package com.dwinovo.numen.core.tools.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * {@code fight}:打。一个动作 {@code attack},占身体、交任务槽。
 *
 * <p><b>不问模型用什么武器</b>——那要看走到跟前时还有多远、有没有视线、还剩几支箭,全是模型在派发那一刻看不到的东西。
 */
public final class FightCommands {

    static final String GROUP = "fight";

    private static final Param<List<EntityRef>> ENTITY_IDS = Param.optional("entity_ids",
            ArgType.list(ArgType.entity()), "The entities to fight, up to 20 distinct ones.")
            .values("runtime entity ids from scan_entities")
            .whenOmitted("fight off every hostile near you");

    private FightCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Combat: attack the entities you name, or every hostile near you.",
                FightCommands::actions);
    }

    private static void actions(CommandGroup fight) {
        fight.server("attack", "Attack specific entities, or fight off every hostile near you.",
                        FightCommands::attack, ENTITY_IDS)
                .example("fight attack --entity_ids 184 207")
                .example("fight attack")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("The body picks how: it closes in and swings when it can reach, shoots with a bow or "
                        + "crossbow when it cannot, keeps its distance from things that explode, and picks the "
                        + "weapon you own that is strongest against that target. It walks over the drops "
                        + "afterwards.")
                .note("Without ids it ends when nothing is coming after you any more; that is the only way to "
                        + "handle things that split (slimes, magma cubes), because splitting gives them new ids.")
                .note("Asks your owner before hitting a pet, a named mob or a villager when their rules say so.")
                .seeAlso("scan entities", "task stop");
    }

    /**
     * 点名的实体受理这一刻按运行期编号找到;找不到的那些照旧交给任务记成丢失。重启后重放的那一行只写找到的那些,
     * 写成它们的 UUID({@link ServerSource#replayedWith}):运行期编号重启后会发给别的东西,照着旧号重放可能打到
     * 毫不相干的一只。一只都找不到就当场失败——那一行没有可写的目标,照着它重放就成了不点名的清场。
     */
    private static void attack(ServerSource src, CommandArgs args) {
        List<EntityRef> named = args.get(ENTITY_IDS);
        if (named == null) {
            TaskDispatch.setTask(src, new CombatOps().attack(src, List.of()));
            return;
        }
        Map<Integer, Entity> found = new LinkedHashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (EntityRef ref : named) {
            Entity e = ref.in(src.companion().serverLevel());
            if (e == null || e == src.companion()) {
                if (ref.id() != null) {
                    ids.add(ref.id());
                }
                continue;
            }
            found.putIfAbsent(e.getId(), e);
            ids.add(e.getId());
        }
        if (found.isEmpty()) {
            src.reply(TaskResult.fail("none of " + named + " is here — scan_entities first, ids do not "
                    + "survive restarts").toJson());
            return;
        }
        List<EntityRef> stable = found.values().stream().map(EntityRef::of).toList();
        TaskDispatch.setTask(src.replayedWith(args.with(ENTITY_IDS, stable)), new CombatOps().attack(src, ids));
    }
}
