package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
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
import net.minecraft.world.inventory.AbstractFurnaceMenu;

import java.util.List;
import java.util.Optional;

/**
 * {@code numen.furnace}:熔炉、高炉、烟熏炉(界面是 {@link AbstractFurnaceMenu} 的那些)。三件事各说各的格:放原料、放燃料、取产物。
 * 都是放下或取走就交回,不等烧完——等多久、站着等还是走开是她的事。
 */
public final class FurnaceApi {

    private static final String KIND = "furnace";
    private static final int[] INPUT = {AbstractFurnaceMenu.INGREDIENT_SLOT};
    private static final int[] FUEL = {AbstractFurnaceMenu.FUEL_SLOT};
    private static final int[] OUTPUT = {AbstractFurnaceMenu.RESULT_SLOT};

    private FurnaceApi() {}

    public static void install(NumenApi numen) {
        numen.api("furnace", "A furnace, blast furnace or smoker: put the input in, put fuel in, take out what is "
                + "cooked.", FurnaceApi.class);
    }

    /** 哪座炉、放什么、放几件。 */
    public record Load(@Doc("The furnace's cell.") BlockPos furnace,
                       @Doc("The item, minecraft:raw_iron.") StackRef item,
                       @Doc("How many.") @Omitted("all you carry of it") @Positional Optional<Integer> count) {}

    @Fn("Put items into a furnace's input slot, to be cooked.")
    @Example("numen.furnace.input({x = 120, y = 64, z = -35}, \"minecraft:raw_iron\", 8)")
    @Note("Walks up to it when it is out of reach, opens it, puts the items in and closes it. It returns at once, "
            + "without waiting for the cooking; fuel goes in with numen.furnace.fuel.")
    @Note("Returns how many went in. None at all fails: no_material when you carry none, failed when the slot holds "
            + "something else or is full.")
    @SeeAlso({"numen.furnace.fuel", "numen.furnace.take", "numen.scan.block"})
    public static Pending<Integer> input(ServerCall call, Load args) {
        return load(call, args, INPUT, "input");
    }

    @Fn("Put items into a furnace's fuel slot.")
    @Example("numen.furnace.fuel({x = 120, y = 64, z = -35}, \"minecraft:coal\", 1)")
    @Example("numen.furnace.fuel(furnace, \"minecraft:oak_planks\")")
    @Note("It goes into the fuel slot even when the item could also be cooked (a log, a wooden tool).")
    @Note("Returns how many went in. None at all fails: no_material when you carry none, failed when the item is not a "
            + "fuel or the slot holds another fuel.")
    @SeeAlso({"numen.furnace.input", "numen.furnace.take"})
    public static Pending<Integer> fuel(ServerCall call, Load args) {
        return load(call, args, FUEL, "fuel");
    }

    private static Pending<Integer> load(ServerCall call, Load args, int[] slots, String role) {
        Move put = Move.put(args.item().in(call.her().getInventory(), call.her().registryAccess()), args.count().orElse(null), slots, role);
        return Stations.at(call, args.furnace(), AbstractFurnaceMenu.class, KIND, List.of(put), () -> put.moved().count());
    }

    /** 哪座炉。 */
    public record Furnace(@Doc("The furnace's cell.") BlockPos furnace) {}

    @Fn("Take everything that is cooked out of a furnace's output slot.")
    @Example("for _, s in ipairs(numen.furnace.take({x = 120, y = 64, z = -35})) do print(s.item, s.count) end")
    @Note("Returns what came out, one entry per kind; an empty list when nothing is cooked yet. What is still cooking "
            + "and how much fuel is left is read with numen.scan.block.")
    @Note("Taking out of someone's furnace may ask your owner first; the call waits for the answer.")
    @SeeAlso({"numen.furnace.input", "numen.scan.block"})
    public static Pending<List<Stations.Taken>> take(ServerCall call, Furnace args) {
        Move take = Move.take(null, null, OUTPUT, "the output", true);
        return Stations.at(call, args.furnace(), AbstractFurnaceMenu.class, KIND, List.of(take),
                () -> Stations.taken(take.moved()));
    }
}
