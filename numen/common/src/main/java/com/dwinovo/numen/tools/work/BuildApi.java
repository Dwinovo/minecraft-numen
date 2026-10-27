package com.dwinovo.numen.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.build.Design;
import com.dwinovo.numen.task.build.BuildCompanionTask;
import com.dwinovo.numen.tools.BuildOps;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Job;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;

/**
 * {@code numen.build}:把一处手够得着的格变成要盖的样子。要盖的样子({@link Design})是一串格(Cells,{@code numen.shape} 画的,或任何
 * 一串 Block)。{@code place} 整份走一遍、每格只试一次:够得着的放,够不着的走到够得着最低一层剩下的格的地方再放;没放好的在回执里
 * 报给模型,要不要再来一轮由模型自己定。{@code diff} 只读,数还差什么、要花什么。
 */
public final class BuildApi {

    private BuildApi() {}

    public static void install(NumenApi numen) {
        numen.codec(Design.class, Design.CODEC);
        numen.api("build", "Building: make the cells of Cells (numen.shape draws them) look like the design, "
                + "walking up to the cells out of reach. numen.build.diff counts what is left and what it costs. "
                + "numen.build.place goes through all of them once and reports what did not go in.", BuildApi.class);
    }

    /** 要盖成的样子。 */
    public record Place(@Doc("What it should look like: Cells (each a Block, the block for that cell written as "
            + "/setblock takes it).") Design design) {}

    @Fn("Make the cells of a design look like it: one pass over all of them, each cell tried once, walking up to the "
            + "cells out of reach; what did not go in is reported.")
    @Example("numen.build.place({{name = \"cobblestone\", pos = {x = 100, y = 64, z = -20}}, "
            + "{name = \"oak_stairs[facing=east]\", pos = {x = 101, y = 64, z = -20}}})")
    @Example("numen.build.place({{name = \"crafting_table\", pos = {x = 101, y = 64, z = -20}}})")
    @Note("It never digs. It places the cells within reach of where you stand, each once; the cells out of reach it walks "
            + "to, standing where the hand reaches the lowest layer left, and places the cells within reach there, and "
            + "so on until every cell has had its turn or it can't get any further. Then it returns what it placed and "
            + "what is left, with the reason for each cell that did not go in. The walk goes its own way like "
            + "`numen.move.to`, digging and building when going around costs more, and is told in the result. "
            + "`numen.build.diff` says what is still to do and where without moving; whether to go over what is left "
            + "again is yours to decide.")
    @Note("Each cell is placed by hand, the way a player does: standing where you are, it looks at a face of a block next to "
            + "the cell (or at the cell itself where grass or snow stands) and clicks it with the block in hand; survival "
            + "goes at four blocks a second. Where no click from here can give the shape the design asks for (nothing to "
            + "click against, a facing or half no click makes), or a cell takes more than one item, the block is written "
            + "straight into the world instead, and `set_directly` in the result counts those cells. Both ways update the "
            + "neighbours the way vanilla does (redstone connects, panes and fences join, stairs take their shape), and "
            + "the world has the last word: a block vanilla drops (a torch with nothing to hold it) is not done.")
    @Note("What was not placed is not a failure: the result's `unplaced` groups those cells by reason (`dig_first`, "
            + "`short_of_material`, `out_of_reach`, `nothing_holds_it`, `blocked_by_entity`, `refused`, `did_not_stay`, "
            + "`no_item`, `liquid`, `left_alone`), each with a sentence saying what stopped them and a hint for the next step. Fix that and "
            + "call it again: what already stands is skipped.")
    @Note("Background work: fails at once with kind out_of_reach when nothing of it can be placed or walked up to yet "
            + "(the cells hold other blocks to dig out first, or nothing holds them), with what is left and a hint; "
            + "nothing starts. When everything already looks like it, it returns at once with nothing placed.")
    @Note("Survival: each cell takes its items as it goes. It places what your stock pays for and reports what you are "
            + "short of (numen.build.diff tells beforehand what the cells cost and what you lack); with none of the items "
            + "at all it places nothing. Where another block stands it places nothing: dig it out first with "
            + "numen.work.dig (numen.build.diff lists those cells). Creative replaces it at once.")
    @Note("Blocks that hang on others (torches, ladders, signs, carpets, flowers, rails, buttons, hanging lanterns) go "
            + "in after the blocks around them. A door or a tall plant is written as its lower half and a bed as its "
            + "foot; the other half comes with it.")
    @Note("Water and lava are not part of what it does: cells of liquid in the design, liquid standing where the design wants "
            + "air, and the water a waterlogged block holds are left out, the other cells go in as usual, and the `liquid` "
            + "group of `unplaced` counts them. Liquid flows, so what stands afterwards would not match the list. Pour it "
            + "from a bucket with numen.use.block; to get rid of it, fill the cell with a block or scoop the source up.")
    @Note("Asks your owner first when their rules say so, for the cells it would change; a refusal stops it with their "
            + "words.")
    @SeeAlso({"numen.build.diff", "numen.task.stop"})
    public static Job<BuildCompanionTask.Placed> place(ServerCall call, Place args) {
        return BuildOps.place(call, args.design());
    }

    @Fn("What a spot still lacks to look like a design, seen from where you stand: how many cells are within reach to "
            + "place, which must be dug out first, how many are out of reach and the lowest nearest of those, and "
            + "what the cells still to do cost.")
    @Example("local d = numen.build.diff({{name = \"cobblestone\", pos = {x = 100, y = 64, z = -20}}, "
            + "{name = \"oak_stairs[facing=east]\", pos = {x = 101, y = 64, z = -20}}})\n"
            + "print(d.left, d.reach, d.far)")
    @Example("local d = numen.build.diff({{name = \"oak_planks\", pos = {x = 100, y = 64, z = -20}}})\n"
            + "print(d.materials.oak_planks, d.short_of)")
    @Note("Instant and read-only.")
    @Note("materials lists every item the cells still to do take, most first, counted the way placing takes them; "
            + "short_of is what of that you do not carry, in survival (absent when building costs nothing). It prices "
            + "the cells you pass, whether you drew them or got them from elsewhere.")
    @Note("A cell is within reach exactly when numen.build.place would place it from here, without walking. `liquid` counts "
            + "the cells of water or lava (and the waterlogged blocks whose water is left out) that numen.build.place "
            + "does not do; they are not in `left` or `materials`.")
    @SeeAlso({"numen.build.place"})
    public static BuildOps.Diff diff(ServerCall call, Place args) {
        return BuildOps.diff(call.her(), args.design());
    }
}
