package com.dwinovo.numen.core.tools.work;

import java.util.List;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.CombatOps;
import com.dwinovo.numen.task.TaskDispatch;

/**
 * {@code fight}:打。一个动作 {@code attack},占身体、交任务槽。
 *
 * <p><b>不问模型用什么武器</b>——那要看走到跟前时还有多远、有没有视线、还剩几支箭,全是模型在派发那一刻看不到的东西。
 */
public final class FightCommands {

    static final String GROUP = "fight";

    private static final Param<List<Integer>> ENTITY_IDS = Param.optional("entity_ids",
            ArgType.list(ArgType.integer()), "The entities to fight, up to 20 distinct ones.")
            .values("runtime entity ids from scan_nearby_entities")
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

    private static void attack(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new CombatOps().attack(src, args.get(ENTITY_IDS)));
    }
}
