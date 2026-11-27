package com.dwinovo.numen.plugins.minecraft.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.plugins.minecraft.build.Design;
import com.dwinovo.numen.plugins.minecraft.task.build.BuildCompanionTask;
import com.dwinovo.numen.plugins.minecraft.tools.BuildOps;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Job;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;

/**
 * {@code minecraft.build}:把一串格变成要盖的样子。要盖的样子({@link Design})是一串格(Cells,{@code minecraft.shape} 画的,或任何
 * 一串 Block)。{@code place} 整份走一遍(站不住的排到队尾、只重试一次):她站在原地放,不看离得多远;没放好的在回执里报给模型,要不要再来一轮
 * 由模型自己定。{@code diff} 只读,数还差什么、要花什么。
 */
public final class BuildApi {

    private BuildApi() {}

    public static void install(NumenApi numen) {
        numen.codec(Design.class, Design.CODEC);
        numen.api("build", "Building: make the cells of Cells (minecraft.shape draws them) look like the design, "
                + "from where you stand, however far the cells are. minecraft.build.diff counts what is left and what it "
                + "costs. minecraft.build.place goes through all of them once and reports what did not go in.",
                BuildApi.class);
    }

    /** 要盖成的样子。 */
    public record Place(@Doc("What it should look like: Cells (each a Block, the block for that cell written as "
            + "/setblock takes it).") Design design) {}

    @Fn("Make the cells of a design look like it: one pass over all of them from where you stand; what did not go "
            + "in is reported.")
    @Example("minecraft.build.place({{name = \"cobblestone\", pos = {x = 100, y = 64, z = -20}}, "
            + "{name = \"oak_stairs[facing=east]\", pos = {x = 101, y = 64, z = -20}}})")
    @Example("minecraft.build.place({{name = \"crafting_table\", pos = {x = 101, y = 64, z = -20}}})")
    @Note("It never digs and never walks: you stay where you stand and every cell is placed from there, however far it "
            + "is and whatever lies in between, lower layers first and the nearer cells of a layer first, thirty cells "
            + "a tick. Then it returns what it placed and what is left, with the reason for each cell that did not go "
            + "in. A cell your body stands in is not placed (`blocked_by_entity`): step out of it with minecraft.move.walk "
            + "and call it again. `minecraft.build.diff` says what is still to do; whether to go over what is left again is "
            + "yours to decide.")
    @Note("Each cell is written into the world as the design says, and the item is taken from your stock once it is "
            + "in. When a cell's turn comes the block is asked whether it can stay there (a torch, a ladder, a flower, a "
            + "rail, a sign need something to hold them): if not, it goes to the end of the queue and is asked once "
            + "more after everything else is placed, so a design may list a torch before the wall it hangs on. Still "
            + "no, and it is left out (`nothing_holds_it`, nothing taken from your stock). A block takes the shape its "
            + "neighbours give it, and the neighbours update the way vanilla does (redstone connects, panes and fences "
            + "join, stairs take their shape). The world has the last word afterwards: a block vanilla drops later "
            + "(a flower whose ground was changed) is reported as not done.")
    @Note("What was not placed is not a failure: the result's `unplaced` groups those cells by reason (`dig_first`, "
            + "`short_of_material`, `nothing_holds_it`, `blocked_by_entity`, `refused`, `did_not_stay`, `no_item`, "
            + "`liquid`, `left_alone`, `not_loaded`), each with a sentence saying what stopped them and a hint for the "
            + "next step. Fix that and call it again: what already stands is skipped.")
    @Note("Background work: fails at once with kind failed when nothing of it can be placed yet (the cells hold other "
            + "blocks to dig out first, or you carry nothing to put there), with what is left and a hint; nothing "
            + "starts. When everything already looks like it, it returns at once with nothing placed.")
    @Note("Survival: each cell takes its items as it goes. It places what your stock pays for and reports what you are "
            + "short of (minecraft.build.diff tells beforehand what the cells cost and what you lack); with none of the items "
            + "at all it places nothing. Where another block stands it places nothing: dig it out first with "
            + "minecraft.work.dig (minecraft.build.diff lists those cells). Creative replaces it at once.")
    @Note("A door or a tall plant is written as its lower half and a bed as its foot; the other half comes with it.")
    @Note("Water and lava are not part of what it does: cells of liquid in the design, liquid standing where the design wants "
            + "air, and the water a waterlogged block holds are left out, the other cells go in as usual, and the `liquid` "
            + "group of `unplaced` counts them. Liquid flows, so what stands afterwards would not match the list. Pour it "
            + "from a bucket with minecraft.use.block; to get rid of it, fill the cell with a block or scoop the source up.")
    @Note("Asks your owner first when their rules say so, for the cells it would change; a refusal stops it with their "
            + "words.")
    @SeeAlso({"minecraft.build.diff", "numen.task.stop"})
    public static Job<BuildCompanionTask.Placed> place(ServerCall call, Place args) {
        return BuildOps.place(call, args.design());
    }

    @Fn("What a spot still lacks to look like a design: how many cells minecraft.build.place puts in now, which must be "
            + "dug out first, and what the cells still to do cost.")
    @Example("local d = minecraft.build.diff({{name = \"cobblestone\", pos = {x = 100, y = 64, z = -20}}, "
            + "{name = \"oak_stairs[facing=east]\", pos = {x = 101, y = 64, z = -20}}})\n"
            + "print(d.left, d.ready, #d.dig)")
    @Example("local d = minecraft.build.diff({{name = \"oak_planks\", pos = {x = 100, y = 64, z = -20}}})\n"
            + "print(d.materials.oak_planks, d.short_of)")
    @Note("Instant and read-only.")
    @Note("materials lists every item the cells still to do take, most first, counted the way placing takes them; "
            + "short_of is what of that you do not carry, in survival (absent when building costs nothing). It prices "
            + "the cells you pass, whether you drew them or got them from elsewhere.")
    @Note("`ready` counts the cells minecraft.build.place would place now, wherever you stand. `liquid` counts the cells "
            + "of water or lava (and the waterlogged blocks whose water is left out) that minecraft.build.place does not "
            + "do; they are not in `left` or `materials`.")
    @SeeAlso({"minecraft.build.place"})
    public static BuildOps.Diff diff(ServerCall call, Place args) {
        return BuildOps.diff(call.her(), args.design());
    }
}
