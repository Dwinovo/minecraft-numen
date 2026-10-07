package com.dwinovo.numen.core.tools.locate;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.task.locate.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.locate.LocateStructureTaskRecord;
import com.dwinovo.numen.core.task.locate.Located;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

/**
 * {@code numen.locate}:找最近的一处结构或群系,和原版的 {@code /locate structure|biome} 同一个意思,只是不要权限、搜索按刻分片。
 *
 * <p>语义是查询,但搜索要在服务端线程上分好几刻读世界,所以两个函数都是一件有界短活({@link ServerCall#sync}):这次调用等结论,结论就是
 * 交回的值。
 */
public final class LocateApi {

    /**
     * 期限量的是身体干活的刻;定位从头到尾站着等搜索,一刻活都不干,期限不走——收工靠搜索自己的环数。这个数只是记录要带的那一格。
     */
    private static final long TIMEOUT_TICKS = 30 * 20;

    private LocateApi() {}

    public static void install(NumenApi numen) {
        numen.api("locate", "Find the nearest structure or biome in the dimension you are in.", LocateApi.class);
    }

    /** 找哪种结构。 */
    public record Structure(@Doc("Structure id or #tag: minecraft:stronghold, minecraft:fortress, "
            + "minecraft:bastion_remnant, minecraft:ancient_city, minecraft:end_city, minecraft:monument, "
            + "minecraft:mansion, minecraft:pillager_outpost, or a family tag such as #minecraft:village or "
            + "#minecraft:ruined_portal; the world_atlas skill lists every one.") String structure) {}

    @Fn("Find the nearest structure of a type: its column, compass direction and distance.")
    @Example("numen.locate.structure(\"minecraft:stronghold\")")
    @Example("numen.locate.structure(\"#minecraft:ruined_portal\")")
    @Note("Searches YOUR CURRENT dimension only: fortresses and bastions are in the Nether, end cities in the End. "
            + "You stand still until it answers; nothing is loaded or changed.")
    @Note("For the stronghold this replaces throwing eyes of ender: save the eyes for the portal frames.")
    @Note("Travel to the column with numen.move.to, then `numen.scan.blocks` finds its actual blocks.")
    @SeeAlso({"numen.locate.biome", "numen.scan.blocks"})
    public static Pending<Located> structure(ServerCall call, Structure args) {
        return call.sync(new LocateStructureTaskRecord(call, deadline(call), args.structure()));
    }

    /** 找哪种群系。 */
    public record Biome(@Doc("Biome id or #tag: minecraft:warped_forest (endermen for pearls), "
            + "minecraft:soul_sand_valley, minecraft:desert, minecraft:plains, minecraft:dark_forest, or a family tag "
            + "such as #minecraft:is_forest or #minecraft:is_ocean; the world_atlas skill lists every one.")
                        String biome) {}

    @Fn("Find the nearest biome of a type: its column, compass direction and distance.")
    @Example("numen.locate.biome(\"minecraft:warped_forest\")")
    @Example("numen.locate.biome(\"#minecraft:is_forest\")")
    @Note("Searches YOUR CURRENT dimension only, about 6400 blocks out. You stand still until it answers; nothing "
            + "is loaded or changed.")
    @Note("Biome edges are fuzzy: the answer is good to about 64 blocks. Travel to the column and confirm with "
            + "`numen.scan.blocks` or `numen.scan.entities` when you arrive.")
    @SeeAlso({"numen.locate.structure", "numen.scan.entities"})
    public static Pending<Located> biome(ServerCall call, Biome args) {
        return call.sync(new LocateBiomeTaskRecord(call, deadline(call), args.biome()));
    }

    private static long deadline(ServerCall call) {
        return call.her().level().getGameTime() + TIMEOUT_TICKS;
    }
}
