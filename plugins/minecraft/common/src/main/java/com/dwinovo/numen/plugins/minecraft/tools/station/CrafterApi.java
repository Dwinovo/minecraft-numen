package com.dwinovo.numen.plugins.minecraft.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackRef;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * {@code minecraft.crafter}:合成器。它的 3x3 九格按行从 0 数到 8;往点名的一格里放东西,把一格开着或关上(关上的格不收东西)。开关一格是
 * {@code ContainerSlotStateChanged};原版只让关空着的格,读回来没变就如实说。
 */
public final class CrafterApi {

    private static final String KIND = "crafter";
    private static final int GRID = 9;

    private CrafterApi() {}

    public static void install(NumenApi numen) {
        numen.api("crafter", "A crafter: put items into the slots of its 3x3 grid, switch a slot on or off.",
                CrafterApi.class);
    }

    /** 哪台合成器、哪一格、放什么、放几件。 */
    public record Put(@Doc("The crafter's cell.") BlockPos crafter,
                      @Doc("The slot of its 3x3 grid, 0 to 8, counted row by row from the top left.") int slot,
                      @Doc("The item, minecraft:iron_ingot.") StackRef item,
                      @Doc("How many.") @Omitted("all you carry of it, up to a stack") @Positional Optional<Integer> count) {}

    @Fn("Put items of one kind from your inventory into one slot of a crafter's grid.")
    @Example("minecraft.crafter.put({x = 120, y = 64, z = -35}, 4, \"minecraft:iron_ingot\", 1)")
    @Note("Walks up to the crafter when it is out of reach, puts the items into that slot and closes it. Returns "
            + "how many went in. A slot that is switched off, or holds another item, takes none: that fails, and "
            + "minecraft.crafter.toggle switches a slot on.")
    @SeeAlso({"minecraft.crafter.toggle", "minecraft.scan.block"})
    public static Pending<Integer> put(ServerCall call, Put args) {
        int slot = slot(args.slot());
        Move put = Move.put(args.item().in(call.her().getInventory(), call.her().registryAccess()), Stations.count("count", args.count()),
                new int[] {slot}, "slot " + slot);
        return Stations.at(call, args.crafter(), CrafterMenu.class, KIND, List.of(
                new Deed((menu, her) -> {
                    if (((CrafterMenu) menu).isSlotDisabled(slot)) {
                        throw new ApiError(ErrorKind.FAILED, "slot " + slot + " of the crafter is switched off and "
                                + "takes nothing", Call.of("minecraft.crafter.toggle", args.crafter(), slot, true));
                    }
                    return null;
                }),
                put), () -> put.moved().count());
    }

    /** 哪台合成器、哪一格、开还是关。 */
    public record Toggle(@Doc("The crafter's cell.") BlockPos crafter,
                         @Doc("The slot of its 3x3 grid, 0 to 8, counted row by row from the top left.") int slot,
                         @Doc("True to switch the slot on (it takes items), false to switch it off.") boolean on) {}

    @Fn("Switch one slot of a crafter's grid on or off.")
    @Example("minecraft.crafter.toggle({x = 120, y = 64, z = -35}, 4, false)")
    @Note("Walks up to the crafter when it is out of reach and sets the slot. Returns true when the slot changed, "
            + "false when it already was that way. A slot that holds items stays on: switching it off fails, saying "
            + "what it holds.")
    @SeeAlso("minecraft.crafter.put")
    public static Pending<Boolean> toggle(ServerCall call, Toggle args) {
        int slot = slot(args.slot());
        boolean on = args.on();
        boolean[] changed = new boolean[1];
        return Stations.at(call, args.crafter(), CrafterMenu.class, KIND, List.of(new Deed((menu, her) -> {
            CrafterMenu crafter = (CrafterMenu) menu;
            String state = on ? "on" : "off";
            if (crafter.isSlotDisabled(slot) != on) {
                return "slot " + slot + " is already " + state;
            }
            her.packets().slotState(slot, on);
            if (crafter.isSlotDisabled(slot) == on) {
                ItemStack held = crafter.getSlot(slot).getItem();
                throw new ApiError(ErrorKind.FAILED, "slot " + slot + " of the crafter stayed " + (on ? "off" : "on")
                        + (held.isEmpty() ? "" : ": it holds " + StackLabel.brief(her, held) + " x" + held.getCount()), null);
            }
            changed[0] = true;
            return "switched slot " + slot + " " + state;
        })), () -> changed[0]);
    }

    /** 合成器的格号得是 0 到 8;不是当场按参数错误退回。 */
    private static int slot(int slot) {
        if (slot < 0 || slot >= GRID) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a crafter's grid slots are 0 to " + (GRID - 1) + "; " + slot
                    + " is not one", null);
        }
        return slot;
    }
}
