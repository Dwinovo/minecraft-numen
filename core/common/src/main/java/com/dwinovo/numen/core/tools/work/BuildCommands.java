package com.dwinovo.numen.core.tools.work;

import java.util.List;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.BlueprintOps;
import com.dwinovo.numen.core.tools.ScaffoldOps;
import com.dwinovo.numen.task.TaskDispatch;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code build}:按图纸施工,以及寻路拿来垫脚的那份方块清单。
 *
 * <p>{@code blueprint} 占身体,交任务槽;其余当场回:{@code blueprints}、{@code blueprint_read}、{@code scaffold} 只读,
 * {@code scaffold_*} 改的是一份登记(不占身体,改完报主人一句)。
 */
public final class BuildCommands {

    static final String GROUP = "build";

    private static final Param<String> FILE = Param.required("file", ArgType.string(),
            "The blueprint, without extension.")
            .values("a name as build blueprints lists it");
    private static final Param<Integer> ANCHOR_X = Param.required("x", ArgType.integer(),
            "Anchor X: the lowest X the structure will occupy.");
    private static final Param<Integer> ANCHOR_Y = Param.required("y", ArgType.integer(),
            "Anchor Y: the floor level, the lowest Y the structure will occupy.");
    private static final Param<Integer> ANCHOR_Z = Param.required("z", ArgType.integer(),
            "Anchor Z: the lowest Z the structure will occupy.");
    private static final Param<Integer> SITE_X = Param.optional("x", ArgType.integer(),
            "Anchor X of a site to check; give x, y and z together.")
            .whenOmitted("read the blueprint alone");
    private static final Param<Integer> SITE_Y = Param.optional("y", ArgType.integer(),
            "Anchor Y (floor level) of the site; see x.");
    private static final Param<Integer> SITE_Z = Param.optional("z", ArgType.integer(),
            "Anchor Z of the site; see x.");
    private static final Param<Integer> ROTATION = Param.optional("rotation", ArgType.integer(),
            "Turn the structure clockwise, in degrees.")
            .values("0, 90, 180 or 270")
            .whenOmitted("keep it as drawn");
    private static final Param<List<ResourceLocation>> BLOCKS = Param.required("blocks", ArgType.list(ArgType.id()),
            "Block ids.");

    private static final ScaffoldOps SCAFFOLD = new ScaffoldOps();

    private BuildCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Blueprint files: list, read, build one; and the blocks you spend as "
                + "scaffolding.", BuildCommands::actions);
    }

    private static void actions(CommandGroup build) {
        build.server("blueprints", "The blueprint files you can build, with their sizes.",
                        (src, args) -> src.reply(BlueprintOps.list(src.companion().getServer())))
                .example("build blueprints")
                .note("Instant and read-only. Files live in the server's schematics/ folder (.litematic, .schem, "
                        + ".nbt, .snbt), so anything the player already downloaded is there.")
                .seeAlso("build blueprint_read", "build blueprint");
        build.server("blueprint_read", "What a blueprint costs: size, cell count, every material, and how its "
                        + "cells spread over height.", BuildCommands::read, FILE, SITE_X, SITE_Y, SITE_Z, ROTATION)
                .example("build blueprint_read japanese_cottage")
                .example("build blueprint_read japanese_cottage --x 100 --y 64 --z -20 --rotation 90")
                .note("Instant and read-only; it does not touch the world. Read it BEFORE proposing a build: in "
                        + "survival the material list is the shopping list, and a large structure takes several "
                        + "gathering trips.")
                .note("With an anchor it also says what is already standing there, what is still missing and "
                        + "what she is short of now: use it to resume a part-built structure or to check a site.")
                .note("The layer profile shows where storeys start, e.g. where a second floor or the roof begins.")
                .seeAlso("build blueprints", "build blueprint");
        build.server("blueprint", "Construct a whole blueprint with its lowest corner at x y z.",
                        BuildCommands::blueprint, FILE, ANCHOR_X, ANCHOR_Y, ANCHOR_Z, ROTATION)
                .example("build blueprint japanese_cottage 100 64 -20")
                .example("build blueprint \"my house\" 100 64 -20 --rotation 180")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("She walks to the site once, then builds from the ground up with exact states (stairs, "
                        + "doors, beds). Air cells in the file CLEAR what stands there; liquid cells are skipped. "
                        + "Pick flat, clear ground big enough for the size.")
                .note("In creative she builds freely. In survival every cell costs one matching item and a whole "
                        + "building will not fit in one inventory: she builds as far as her stock goes, then stops "
                        + "and reports what is still needed. Restock her and send the same line again; finished "
                        + "cells are skipped, so she carries on where she stopped.")
                .note("Asks your owner first when their rules say so, for the cells it would change.")
                .seeAlso("build blueprint_read", "task stop");
        build.server("scaffold", "Your scaffolding list: the blocks pathfinding may spend to pillar up, bridge a "
                        + "gap or step over a ledge.", (src, args) -> src.reply(SCAFFOLD.read(src.companion())))
                .example("build scaffold")
                .note("Instant and read-only. The reply carries the whole list and what you carry that is not on "
                        + "it, so one call is enough to decide.")
                .note("The list persists across sessions and is used by every move you make, reflexes such as "
                        + "fleeing included.")
                .seeAlso("build scaffold_add", "build scaffold_remove");
        build.server("scaffold_add", "Add blocks you are willing to spend as scaffolding.",
                        (src, args) -> src.reply(SCAFFOLD.add(src.companion(), ids(args))), BLOCKS)
                .example("build scaffold_add minecraft:cobblestone minecraft:cobbled_deepslate")
                .note("Anything listed WILL be consumed and never comes back: list what is junk here and now. "
                        + "Cobblestone is junk in a mineshaft and precious in the End.")
                .note("Instant; your owner is told what you changed.")
                .seeAlso("build scaffold", "build scaffold_remove");
        build.server("scaffold_remove", "Take blocks off your scaffolding list.",
                        (src, args) -> src.reply(SCAFFOLD.remove(src.companion(), ids(args))), BLOCKS)
                .example("build scaffold_remove minecraft:dirt")
                .note("Instant; your owner is told what you changed.")
                .seeAlso("build scaffold", "build scaffold_add");
        build.server("scaffold_set", "Replace your whole scaffolding list.",
                        (src, args) -> src.reply(SCAFFOLD.set(src.companion(), ids(args))), BLOCKS)
                .example("build scaffold_set minecraft:netherrack")
                .note("Instant; your owner is told what you changed. To allow nothing at all, use "
                        + "build scaffold_clear.")
                .seeAlso("build scaffold", "build scaffold_clear");
        build.server("scaffold_clear", "Empty your scaffolding list, so no block may be spent.",
                        (src, args) -> src.reply(SCAFFOLD.clear(src.companion())))
                .example("build scaffold_clear")
                .note("A real choice for when what you carry is earmarked (the dirt is for a build): she then "
                        + "cannot pillar or bridge at all, and routes that need it fail until you add some back.")
                .note("Instant; your owner is told what you changed.")
                .seeAlso("build scaffold_add");
    }

    private static List<String> ids(CommandArgs args) {
        return args.get(BLOCKS).stream().map(ResourceLocation::toString).toList();
    }

    /** 顺时针转几个 90°:度数按 360 取模再除以 90,没给是不转。 */
    private static int quarters(CommandArgs args) {
        Integer degrees = args.get(ROTATION);
        return degrees == null ? 0 : Math.floorMod(degrees, 360) / 90;
    }

    private static void read(ServerSource src, CommandArgs args) {
        Integer x = args.get(SITE_X);
        Integer y = args.get(SITE_Y);
        Integer z = args.get(SITE_Z);
        boolean anchored = x != null && y != null && z != null;
        if (!anchored && (x != null || y != null || z != null)) {
            throw new IllegalArgumentException("a site needs all of --x, --y and --z (its lowest corner);"
                    + " leave all three out to read the blueprint alone");
        }
        src.reply(BlueprintOps.read(src.companion(), args.get(FILE), anchored ? new BlockPos(x, y, z) : null,
                quarters(args)));
    }

    private static void blueprint(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, BlueprintOps.build(src, args.get(FILE),
                new BlockPos(args.get(ANCHOR_X), args.get(ANCHOR_Y), args.get(ANCHOR_Z)), quarters(args)));
    }
}
