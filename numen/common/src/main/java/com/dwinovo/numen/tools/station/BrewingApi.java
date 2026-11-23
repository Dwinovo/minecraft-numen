package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.Stacks;
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
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * {@code numen.brewing}:酿造台。四件事各说各的格:放瓶子(三格,一格一瓶)、放材料、放燃料(烈焰粉)、把三格里的瓶子取出来。都是放下或
 * 取走就交回,不等酿完——等多久、站着等还是走开是她的事。
 */
public final class BrewingApi {

    private static final String KIND = "brewing stand";
    private static final int[] BOTTLES = {0, 1, 2};
    private static final int[] INGREDIENT = {3};
    private static final int[] FUEL = {4};

    private BrewingApi() {}

    public static void install(NumenApi numen) {
        numen.api("brewing", "A brewing stand: put bottles in, put the ingredient in, put fuel in, take the bottles out.",
                BrewingApi.class);
    }

    /** 哪座酿造台、放什么、放几件。 */
    public record Load(@Doc("The brewing stand's cell.") BlockPos stand,
                       @Doc("The item, minecraft:potion.") StackRef item,
                       @Doc("How many.") @Omitted("all you carry of it that fit") @Positional Optional<Integer> count) {}

    @Fn("Put bottles into a brewing stand's three bottle slots, one bottle a slot.")
    @Example("numen.brewing.bottles({x = 120, y = 64, z = -35}, \"minecraft:potion\")")
    @Note("Walks up to the stand when it is out of reach, puts the bottles in and closes it. It returns at once, "
            + "without waiting for the brewing. The bottles taken are the first of that item in your inventory; "
            + "pass an entry of numen.inv.items() to put in one kind of potion only.")
    @Note("Returns how many went in: fewer than asked when the free slots ran out. None at all fails: no_material "
            + "when you carry none, failed when no slot is free or the item is no bottle.")
    @SeeAlso({"numen.brewing.ingredient", "numen.brewing.fuel", "numen.brewing.take"})
    public static Pending<Integer> bottles(ServerCall call, Load args) {
        return load(call, args.stand(), args.item().in(call.her().getInventory(), call.her().registryAccess()), args.count(), BOTTLES, "bottles");
    }

    @Fn("Put items into a brewing stand's ingredient slot.")
    @Example("numen.brewing.ingredient({x = 120, y = 64, z = -35}, \"minecraft:nether_wart\", 1)")
    @Note("It returns at once, without waiting for the brewing. Returns how many went in. None at all fails: "
            + "no_material when you carry none, failed when the item is no brewing ingredient or the slot holds "
            + "another one.")
    @SeeAlso({"numen.brewing.bottles", "numen.brewing.fuel", "numen.scan.block"})
    public static Pending<Integer> ingredient(ServerCall call, Load args) {
        return load(call, args.stand(), args.item().in(call.her().getInventory(), call.her().registryAccess()), args.count(), INGREDIENT,
                "the ingredient");
    }

    /** 哪座酿造台、放几件燃料。 */
    public record Fuel(@Doc("The brewing stand's cell.") BlockPos stand,
                       @Doc("How many blaze powder.") @Omitted("all you carry") @Positional Optional<Integer> count) {}

    @Fn("Put blaze powder into a brewing stand's fuel slot.")
    @Example("numen.brewing.fuel({x = 120, y = 64, z = -35}, 1)")
    @Note("Returns how many went in. None at all fails: no_material when you carry no blaze powder, failed when "
            + "the slot is full.")
    @SeeAlso({"numen.brewing.bottles", "numen.brewing.ingredient"})
    public static Pending<Integer> fuel(ServerCall call, Fuel args) {
        return load(call, args.stand(), Stacks.Which.of(Items.BLAZE_POWDER), args.count(), FUEL, "fuel");
    }

    private static Pending<Integer> load(ServerCall call, BlockPos stand, Stacks.Which item, Optional<Integer> count,
                                         int[] slots, String role) {
        Move put = Move.put(item, Stations.count("count", count), slots, role);
        return Stations.at(call, stand, BrewingStandMenu.class, KIND, List.of(put), () -> put.moved().count());
    }

    /** 哪座酿造台。 */
    public record Stand(@Doc("The brewing stand's cell.") BlockPos stand) {}

    @Fn("Take every bottle out of a brewing stand's three bottle slots.")
    @Example("for _, s in ipairs(numen.brewing.take({x = 120, y = 64, z = -35})) do print(s.item, s.count) end")
    @Note("Returns what came out, one entry per kind; an empty list when the slots hold no bottle. It takes them "
            + "as they are, brewed or not: what the stand holds is read with numen.scan.block, and what the bottles "
            + "you took are is in numen.inv.items.")
    @Note("Taking out of someone's brewing stand may ask your owner first; the call waits for the answer.")
    @SeeAlso({"numen.brewing.bottles", "numen.scan.block", "numen.inv.items"})
    public static Pending<List<Stations.Taken>> take(ServerCall call, Stand args) {
        Move take = Move.take(null, null, BOTTLES, "the bottle slots", true);
        return Stations.at(call, args.stand(), BrewingStandMenu.class, KIND, List.of(take),
                () -> Stations.taken(take.moved()));
    }
}
