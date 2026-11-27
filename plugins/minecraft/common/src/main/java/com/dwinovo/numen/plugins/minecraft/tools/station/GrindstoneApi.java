package com.dwinovo.numen.plugins.minecraft.tools.station;

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
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Move;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.GrindstoneMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code minecraft.grindstone}:砂轮。放一件,磨掉它的附魔;放两件同样的,并成一件修好的。结果格有东西就取走,没有就说这一件(这两件)磨不出
 * 东西。
 */
public final class GrindstoneApi {

    private static final String KIND = "grindstone";
    private static final int[] ITEM = {GrindstoneMenu.INPUT_SLOT};
    private static final int[] OTHER = {GrindstoneMenu.ADDITIONAL_SLOT};
    private static final int[] RESULT = {GrindstoneMenu.RESULT_SLOT};
    private static final int[] INPUTS = {GrindstoneMenu.INPUT_SLOT, GrindstoneMenu.ADDITIONAL_SLOT};

    private GrindstoneApi() {}

    public static void install(NumenApi numen) {
        numen.api("grindstone", "A grindstone: take the enchantments off an item, or repair an item with another of "
                + "the same kind.", GrindstoneApi.class);
    }

    /** 哪座砂轮、哪件东西。 */
    public record Disenchant(@Doc("The grindstone's cell.") BlockPos grindstone,
                             @Doc("The enchanted item, minecraft:diamond_sword: one you carry.") StackRef item) {}

    @Fn("Take the enchantments off an item you carry at a grindstone.")
    @Example("minecraft.grindstone.disenchant({x = 120, y = 64, z = -35}, \"minecraft:diamond_sword\")")
    @Note("Walks up to the grindstone when it is out of reach, puts the item in and takes the result. The item "
            + "taken is the first of that item in your inventory; pass an entry of minecraft.inv.items() to name which "
            + "one.")
    @Note("Returns the item as it is now. When the grindstone makes nothing of it, it fails saying so, and the "
            + "item comes back to you.")
    @SeeAlso("minecraft.grindstone.repair")
    public static Pending<Stations.Product> disenchant(ServerCall call, Disenchant args) {
        return grind(call, args.grindstone(), args.item().in(call.her().getInventory(), call.her().registryAccess()), null);
    }

    /** 哪座砂轮、哪两件东西。 */
    public record Repair(@Doc("The grindstone's cell.") BlockPos grindstone,
                         @Doc("The damaged item, minecraft:iron_pickaxe: one you carry.") StackRef item,
                         @Doc("The item it is combined with: another of the same item.") StackRef other) {}

    @Fn("Repair an item you carry at a grindstone by combining it with another of the same item.")
    @Example("minecraft.grindstone.repair({x = 120, y = 64, z = -35}, \"minecraft:iron_pickaxe\", \"minecraft:iron_pickaxe\")")
    @Note("Walks up to the grindstone when it is out of reach, puts the two in and takes the one item they "
            + "become. The two taken are the first of each in your inventory; pass entries of minecraft.inv.items() to "
            + "name which two.")
    @Note("Returns the item as it is now. When the two make nothing together it fails saying so, and they come "
            + "back to you.")
    @SeeAlso({"minecraft.grindstone.disenchant", "minecraft.anvil.combine"})
    public static Pending<Stations.Product> repair(ServerCall call, Repair args) {
        return grind(call, args.grindstone(), args.item().in(call.her().getInventory(), call.her().registryAccess()),
                args.other().in(call.her().getInventory(), call.her().registryAccess()));
    }

    /** @param other 并进去的另一件;只磨掉附魔是 null */
    private static Pending<Stations.Product> grind(ServerCall call, BlockPos grindstone, Stacks.Which item,
                                                   Stacks.Which other) {
        AtomicReference<Stations.Product> made = new AtomicReference<>();
        List<Step> steps = new ArrayList<>();
        steps.add(Move.put(item, 1, ITEM, "the item to grind"));
        if (other != null) {
            steps.add(Move.put(other, 1, OTHER, "the item to combine it with"));
        }
        steps.add(Stations.result(GrindstoneMenu.RESULT_SLOT, made, () -> "the grindstone shows no result for "
                + Stations.id(item.item()) + (other == null ? "" : " with " + Stations.id(other.item()))));
        steps.add(Move.take(null, null, RESULT, "the result", false));
        steps.add(Move.take(null, null, INPUTS, null, true));
        return Stations.at(call, grindstone, GrindstoneMenu.class, KIND, steps, made::get);
    }
}
