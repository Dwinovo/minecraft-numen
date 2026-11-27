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
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code minecraft.cartography}:制图台。对一张地图做三件事之一,各用掉一样东西:放大(纸)、复制(空地图)、锁定(玻璃板)。地图和那一样各进
 * 各的格,做得成结果格就有,取走;做不成就如实说。
 */
public final class CartographyApi {

    private static final String KIND = "cartography table";
    private static final int[] MAP = {CartographyTableMenu.MAP_SLOT};
    private static final int[] OTHER = {CartographyTableMenu.ADDITIONAL_SLOT};
    private static final int[] RESULT = {CartographyTableMenu.RESULT_SLOT};
    private static final int[] INPUTS = {CartographyTableMenu.MAP_SLOT, CartographyTableMenu.ADDITIONAL_SLOT};

    private CartographyApi() {}

    public static void install(NumenApi numen) {
        numen.api("cartography", "A cartography table: extend a map, copy it, or lock it.", CartographyApi.class);
    }

    /** 哪张制图台、哪张地图。 */
    public record OnMap(@Doc("The cartography table's cell.") BlockPos cartographyTable,
                        @Doc("The map, minecraft:filled_map: one you carry.") StackRef map) {}

    @Fn("Extend a map you carry at a cartography table, using one paper: the result covers a larger area.")
    @Example("minecraft.cartography.extend({x = 120, y = 64, z = -35}, \"minecraft:filled_map\")")
    @Note("Walks up to the table when it is out of reach, puts the map and one paper in and takes the result. The "
            + "map taken is the first in your inventory; pass an entry of minecraft.inv.items() to name which map.")
    @Note("Returns the map as it is now. When the table makes nothing of it, it fails saying so, and the map and "
            + "the paper come back to you.")
    @SeeAlso({"minecraft.cartography.copy", "minecraft.cartography.lock"})
    public static Pending<Stations.Product> extend(ServerCall call, OnMap args) {
        return work(call, args, Items.PAPER, "paper");
    }

    @Fn("Copy a map you carry at a cartography table, using one empty map: you get two of the same map.")
    @Example("minecraft.cartography.copy({x = 120, y = 64, z = -35}, \"minecraft:filled_map\")")
    @Note("Walks up to the table when it is out of reach, puts the map and one empty map in and takes the result. "
            + "Returns the two maps.")
    @SeeAlso({"minecraft.cartography.extend", "minecraft.cartography.lock"})
    public static Pending<Stations.Product> copy(ServerCall call, OnMap args) {
        return work(call, args, Items.MAP, "an empty map");
    }

    @Fn("Lock a map you carry at a cartography table, using one glass pane: it no longer changes with the world.")
    @Example("minecraft.cartography.lock({x = 120, y = 64, z = -35}, \"minecraft:filled_map\")")
    @Note("Walks up to the table when it is out of reach, puts the map and one glass pane in and takes the result. "
            + "Returns the map as it is now. When the table makes nothing of it, it fails saying so, and the map "
            + "and the glass pane come back to you.")
    @SeeAlso({"minecraft.cartography.extend", "minecraft.cartography.copy"})
    public static Pending<Stations.Product> lock(ServerCall call, OnMap args) {
        return work(call, args, Items.GLASS_PANE, "a glass pane");
    }

    /**
     * 三件事同一套步骤,只差放进去的那一样。
     *
     * @param with     和地图一起放进去的那一样
     * @param withName 它在话里的叫法
     */
    private static Pending<Stations.Product> work(ServerCall call, OnMap args, Item with, String withName) {
        AtomicReference<Stations.Product> made = new AtomicReference<>();
        return Stations.at(call, args.cartographyTable(), CartographyTableMenu.class, KIND, List.of(
                Move.put(args.map().in(call.her().getInventory(), call.her().registryAccess()), 1, MAP, "the map"),
                Move.put(Stacks.Which.of(with), 1, OTHER, "what it is worked with"),
                Stations.result(CartographyTableMenu.RESULT_SLOT, made, () -> "the cartography table shows no result "
                        + "for this map with " + withName),
                Move.take(null, null, RESULT, "the result", false),
                Move.take(null, null, INPUTS, null, true)), made::get);
    }
}
