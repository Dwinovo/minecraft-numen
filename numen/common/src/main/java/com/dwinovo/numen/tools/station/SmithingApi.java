package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackRef;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.SmithingMenu;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code numen.smithing}:锻造台。模板、底材、添加物各进各的格,三样做得出东西结果格就有,取走;做不出就说这三样不成。
 */
public final class SmithingApi {

    private static final String KIND = "smithing table";
    private static final int[] TEMPLATE = {SmithingMenu.TEMPLATE_SLOT};
    private static final int[] BASE = {SmithingMenu.BASE_SLOT};
    private static final int[] ADDITION = {SmithingMenu.ADDITIONAL_SLOT};
    private static final int[] RESULT = {SmithingMenu.RESULT_SLOT};
    private static final int[] INPUTS = {SmithingMenu.TEMPLATE_SLOT, SmithingMenu.BASE_SLOT, SmithingMenu.ADDITIONAL_SLOT};

    private SmithingApi() {}

    public static void install(NumenApi numen) {
        numen.api("smithing", "A smithing table: make an item from a template, a base item and an addition.",
                SmithingApi.class);
    }

    /** 哪张锻造台、三样东西。 */
    public record Forge(@Doc("The smithing table's cell.") BlockPos smithingTable,
                        @Doc("The smithing template, minecraft:netherite_upgrade_smithing_template.") StackRef template,
                        @Doc("The item it is applied to, minecraft:diamond_sword.") StackRef base,
                        @Doc("The material added, minecraft:netherite_ingot.") StackRef addition) {}

    @Fn("Make an item at a smithing table from a template, a base item and an addition you carry.")
    @Example("numen.smithing.forge({x = 120, y = 64, z = -35}, \"minecraft:netherite_upgrade_smithing_template\", "
            + "\"minecraft:diamond_sword\", \"minecraft:netherite_ingot\")")
    @Note("Walks up to the table when it is out of reach, puts one of each in and takes the result. The base "
            + "taken is the first of that item in your inventory; pass an entry of numen.inv.items() to name which "
            + "one.")
    @Note("Returns the item made. When the three make nothing together it fails saying so, and they come back to "
            + "you.")
    @SeeAlso("numen.inv.recipes")
    public static Pending<Stations.Product> forge(ServerCall call, Forge args) {
        Stacks.Which template = args.template().in(call.her().getInventory(), call.her().registryAccess());
        Stacks.Which base = args.base().in(call.her().getInventory(), call.her().registryAccess());
        Stacks.Which addition = args.addition().in(call.her().getInventory(), call.her().registryAccess());
        AtomicReference<Stations.Product> made = new AtomicReference<>();
        return Stations.at(call, args.smithingTable(), SmithingMenu.class, KIND, List.of(
                Move.put(template, 1, TEMPLATE, "the template"),
                Move.put(base, 1, BASE, "the base"),
                Move.put(addition, 1, ADDITION, "the addition"),
                Stations.result(SmithingMenu.RESULT_SLOT, made, () -> "the smithing table shows no result: "
                        + Stations.id(template.item()) + ", " + Stations.id(base.item()) + " and "
                        + Stations.id(addition.item()) + " make nothing together"),
                Move.take(null, null, RESULT, "the result", false),
                Move.take(null, null, INPUTS, null, true)), made::get);
    }
}
