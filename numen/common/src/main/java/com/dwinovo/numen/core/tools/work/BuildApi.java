package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.build.Design;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.task.build.BuildCompanionTask;
import com.dwinovo.numen.core.tools.BuildOps;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * {@code numen.build}:把一处手够得着的格变成要盖的样子。要盖的样子({@link Design})是一串格(Cells,{@code numen.shape} 画的,或任何
 * 一串 Block),或一份蓝图文件摆在哪儿({@code numen.build.blueprint} 交回的那张表,格子留在文件里)。{@code place} 只放站在原地够得着
 * 的格、每格轮到一次就收场,{@code diff} 数还差什么;走到够得着的地方、挖开挡着的、一轮轮放到底,是库里的 {@code numen.build.raise}。
 */
public final class BuildApi {

    private BuildApi() {}

    public static void install(NumenApi numen) {
        numen.codec(Design.class, Design.CODEC);
        numen.api("build", "Building: make the cells your hand reaches from where you stand look like Cells "
                + "(numen.shape draws them) or a blueprint file. numen.build.raise (library) walks the site and builds "
                + "all of it.", BuildApi.class);
    }

    /** 哪个文件、摆在哪、转多少。 */
    public record Blueprint(@Doc("The blueprint file: a .litematic, .schem, .nbt or .snbt in the server's schematics "
            + "folder, without its extension.") String name,
                            @Doc("The world cell the file's lowest north-west corner goes to: what the file has at its "
                                    + "0 0 0 is built there. Given the ground's own y, its lowest level replaces the top "
                                    + "ground block; given one more it sits on top of the ground.") BlockPos origin,
                            @Doc("Turn it clockwise seen from above, about its origin, which stays where it is: 0, 90, "
                                    + "180 or 270; at 90 the file's +x runs south and what faced north faces east.")
                            @Omitted("keep it as drawn") Optional<Integer> rotation) {}

    @Fn("A blueprint file placed at a spot: its size, cells and materials, for numen.build.place and numen.build.diff.")
    @Example("local house = numen.build.blueprint(\"japanese_cottage\", {x = 100, y = 64, z = -20})")
    @Example("numen.build.blueprint(\"tower\", {x = 100, y = 64, z = -20}, {rotation = 90})")
    @Note("Instant and read-only: it reads the file once and returns where and how it goes with what it costs; nothing "
            + "is built. A name that is not a file fails with not_found and names the files there are.")
    @SeeAlso({"numen.build.place", "numen.build.diff", "numen.build.raise"})
    public static BuildOps.Blueprint blueprint(ServerCall call, Blueprint args) {
        return BuildOps.blueprint(call.her(), args.name(), args.origin(),
                Placement.quarters(args.rotation().orElse(null)));
    }

    /** 要盖成的样子。 */
    public record Place(@Doc("What it should look like: Cells (each a Block, the block for that cell written as "
            + "/setblock takes it), or a Blueprint from numen.build.blueprint.") Design design) {}

    @Fn("Make the cells of a design that your hand reaches from where you stand look like it: each such cell gets its "
            + "turn once.")
    @Example("numen.build.place({{name = \"cobblestone\", pos = {x = 100, y = 64, z = -20}}, "
            + "{name = \"oak_stairs[facing=east]\", pos = {x = 101, y = 64, z = -20}}})")
    @Example("numen.build.place({{name = \"crafting_table\", pos = {x = 101, y = 64, z = -20}}})")
    @Example("numen.build.place(numen.build.blueprint(\"tower\", {x = 100, y = 64, z = -20}))")
    @Note("It never walks and never digs: it places only the cells within reach of where you stand, each once, then "
            + "returns how many it placed and how many are left. `numen.build.diff` says what is still to do and where; "
            + "`numen.build.raise` (library) walks the site, digs what is in the way and calls this until the whole "
            + "building stands.")
    @Note("Background work: fails at once with kind out_of_reach when nothing of it is within reach to place, with how "
            + "many cells are left and a hint with the numen.move.to call to the lowest nearest one; nothing starts. "
            + "When everything already looks like it, it returns at once with nothing placed.")
    @Note("A blueprint at the same dimension, spot and rotation is the same building: placing it again adds what is "
            + "missing, replaces what differs, and removes only blocks you placed there before that the file no longer "
            + "has and that nobody has changed since. Cells are only those cells.")
    @Note("Survival: Cells are priced as a whole before the first block and refused, placing nothing, when anything is "
            + "short; a blueprint builds as far as your stock goes. Where another block stands it places nothing: dig "
            + "it out first with numen.work.dig (numen.build.diff lists those cells). Creative replaces it at once.")
    @Note("Asks your owner first when their rules say so, for the cells it would change; a refusal stops it with their "
            + "words.")
    @SeeAlso({"numen.build.diff", "numen.build.raise", "numen.task.stop"})
    public static Job<BuildCompanionTask.Placed> place(ServerCall call, Place args) {
        return BuildOps.place(call, args.design());
    }

    @Fn("What a spot still lacks to look like a design, seen from where you stand: how many cells are within reach to "
            + "place, which must be dug out first, how many are out of reach and the lowest nearest of those.")
    @Example("local d = numen.build.diff(numen.build.blueprint(\"tower\", {x = 100, y = 64, z = -20}))\n"
            + "print(d.left, d.reach, d.far)")
    @Note("Instant and read-only.")
    @Note("A cell is within reach exactly when numen.build.place would place it from here.")
    @SeeAlso({"numen.build.place", "numen.build.raise"})
    public static BuildOps.Diff diff(ServerCall call, Place args) {
        return BuildOps.diff(call.her(), args.design());
    }
}
