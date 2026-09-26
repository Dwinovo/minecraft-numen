package com.dwinovo.numen.core.tools.locate;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.locate.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.locate.LocateStructureTaskRecord;
import com.dwinovo.numen.task.TaskDispatch;

/**
 * {@code locate}:找最近的一处结构或群系,和原版的 {@code /locate structure|biome} 同一个意思,只是不要权限、搜索按刻分片。
 *
 * <p>语义是查询,但搜索要在服务端线程上分好几刻读世界,所以两个动作都是任务槽里的一次同步短活({@code runSync}):
 * 这次调用挂着等结论,结论就是回执。都不提升——一趟远行才用得上一次,走 {@code command} 就够了。
 */
public final class LocateCommands {

    /**
     * 期限量的是身体干活的刻;定位从头到尾站着等搜索,一刻活都不干,期限不走——收工靠搜索自己的环数。
     * 这个数只是记录要带的那一格。
     */
    private static final long TIMEOUT_TICKS = 30 * 20;

    private static final Param<String> STRUCTURE = Param.required("structure", ArgType.idOrTag(),
            "Structure id (e.g. minecraft:fortress) or #tag (e.g. #minecraft:village).")
            .values("minecraft:stronghold, minecraft:fortress, minecraft:bastion_remnant, minecraft:ancient_city, "
                    + "minecraft:end_city, minecraft:monument, minecraft:mansion, minecraft:pillager_outpost, or a "
                    + "family tag such as #minecraft:village or #minecraft:ruined_portal; the world_atlas skill "
                    + "lists every one");
    private static final Param<String> BIOME = Param.required("biome", ArgType.idOrTag(),
            "Biome id (e.g. minecraft:warped_forest) or #tag (e.g. #minecraft:is_forest).")
            .values("minecraft:warped_forest (endermen for pearls), minecraft:soul_sand_valley, minecraft:desert, "
                    + "minecraft:plains, minecraft:dark_forest, or a family tag such as #minecraft:is_forest or "
                    + "#minecraft:is_ocean; the world_atlas skill lists every one");

    private LocateCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands("locate", "Find the nearest structure or biome in the dimension you are in.",
                LocateCommands::actions);
    }

    private static void actions(CommandGroup locate) {
        locate.server("structure", "Find the nearest structure of a type: its coordinates, compass direction and "
                        + "distance.",
                        LocateCommands::structure, STRUCTURE)
                .example("locate structure minecraft:stronghold")
                .example("locate structure #minecraft:ruined_portal")
                .note("Searches YOUR CURRENT dimension only: fortresses and bastions are in the Nether, end cities "
                        + "in the End. You stand still until it answers; nothing is loaded or changed.")
                .note("For the stronghold this replaces throwing eyes of ender: save the eyes for the portal frames.")
                .note("The y it gives is approximate: travel by x/z, then scan blocks when you arrive.")
                .seeAlso("locate biome", "scan blocks");
        locate.server("biome", "Find the nearest biome of a type: its coordinates, compass direction and distance.",
                        LocateCommands::biome, BIOME)
                .example("locate biome minecraft:warped_forest")
                .example("locate biome #minecraft:is_forest")
                .note("Searches YOUR CURRENT dimension only, about 6400 blocks out. You stand still until it "
                        + "answers; nothing is loaded or changed.")
                .note("Biome edges are fuzzy: the answer is good to about 64 blocks. Travel to the x/z, pick a "
                        + "sensible y, and confirm with scan blocks or scan entities when you arrive.")
                .seeAlso("locate structure", "scan entities");
    }

    private static void structure(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(),
                new LocateStructureTaskRecord(src, deadline(src), args.get(STRUCTURE)), src::reply);
    }

    private static void biome(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(), new LocateBiomeTaskRecord(src, deadline(src), args.get(BIOME)),
                src::reply);
    }

    private static long deadline(ServerSource src) {
        return src.companion().level().getGameTime() + TIMEOUT_TICKS;
    }
}
