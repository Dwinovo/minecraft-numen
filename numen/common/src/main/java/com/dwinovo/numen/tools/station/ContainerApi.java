package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.CellOrEntity;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Optional;

/**
 * {@code numen.container}:往一样装东西的东西里放、从里面取——箱子、木桶、漏斗、潜影盒,和没有自己那一组函数的机器。放进哪一格由它
 * 自己的界面分(和按住 Shift 点一样),所以不用认得它是什么;要说清放进哪一格的(熔炉的燃料)有自己的一组。
 */
public final class ContainerApi {

    private static final String KIND = "container";

    private ContainerApi() {}

    public static void install(NumenApi numen) {
        numen.api("container", "Anything that keeps items in slots — a chest, a barrel, a hopper, a shulker box, a machine "
                + "with no functions of its own: put items in, take items out.", ContainerApi.class);
    }

    /** 往哪儿放、放什么、放几件。 */
    public record Put(@Doc("The container: its cell (a Pos, a Block), or an entity that opens one when right-clicked (a "
            + "chest minecart).") CellOrEntity target,
                      @Doc("The item, minecraft:cobblestone.") Item item,
                      @Doc("How many.") @Omitted("all you carry of it") @Positional Optional<Integer> count) {}

    @Fn("Put items of one kind from your inventory into a container: all of them, or count.")
    @Example("numen.container.put({x = 120, y = 64, z = -35}, \"minecraft:cobblestone\")")
    @Example("numen.container.put(chest, \"minecraft:coal\", 8)")
    @Note("Walks up to it when it is out of reach, opens it, puts the items in and closes it; the walk is told in the "
            + "result. The container decides which slot each stack lands in.")
    @Note("Returns how many went in; fewer than asked when it ran out of room. None at all fails: no_material when you "
            + "carry none, failed when there is no room.")
    @Note("What it holds is read with numen.scan.block, without opening it.")
    @SeeAlso({"numen.container.take", "numen.scan.block"})
    public static Pending<Integer> put(ServerCall call, Put args) {
        Move put = Move.put(args.item(), args.count().orElse(null), null, null);
        return Stations.at(call, args.target(), null, KIND, List.of(put), () -> put.moved().count());
    }

    /** 从哪儿取、取什么、取几件。 */
    public record Take(@Doc("The container: its cell (a Pos, a Block), or an entity that opens one when right-clicked.")
                       CellOrEntity target,
                       @Doc("The item, minecraft:iron_ingot.") Item item,
                       @Doc("How many.") @Omitted("all it holds of it") @Positional Optional<Integer> count) {}

    @Fn("Take items of one kind out of a container into your inventory: all of them, or count.")
    @Example("numen.container.take({x = 120, y = 64, z = -35}, \"minecraft:iron_ingot\")")
    @Example("numen.container.take(chest, \"minecraft:bread\", 3)")
    @Note("Walks up to it when it is out of reach, opens it, takes the items and closes it; the walk is told in the "
            + "result.")
    @Note("Returns how many came out; fewer than asked when it held no more or your inventory filled up. None at all "
            + "fails: not_found when it holds none.")
    @Note("Taking out of someone's container may ask your owner first; the call waits for the answer.")
    @SeeAlso({"numen.container.put", "numen.scan.block"})
    public static Pending<Integer> take(ServerCall call, Take args) {
        Move take = Move.take(args.item(), args.count().orElse(null), null, null, false);
        return Stations.at(call, args.target(), null, KIND, List.of(take), () -> take.moved().count());
    }
}
