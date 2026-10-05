package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import java.util.List;
import java.util.Optional;

/**
 * {@code tlm.altar}:车万女仆祭坛上能合成什么。只查配方(车万女仆的配方类型 {@code altar_crafting}),不替她算够不够;
 * 本类不碰车万女仆的类,只经 {@link Maids}。
 */
public final class AltarApi {

    private AltarApi() {}

    /** 配方的一格材料。 */
    @Doc("One ingredient of an altar recipe: any one of these items will do.")
    public record Need(List<String> items) {}

    /** 一条祭坛配方。 */
    @Doc("One altar recipe.")
    public record Recipe(@Doc("The recipe id.") String id,
                         @Doc("What it makes, when it makes an item.") Optional<MaidApi.Held> item,
                         @Doc("The entity it spawns instead, when it does not make an item: touhou_little_maid:maid "
                                 + "revives a maid from her film.") Optional<String> spawns,
                         @Doc("What goes on the altar, one entry per pedestal.") List<Need> needs,
                         @Doc("The power points it takes from you.") double power) {}

    @Fn("Every recipe of the Touhou Little Maid altar: what it makes, what it needs, and the power points.")
    @Example("for _, r in ipairs(tlm.altar.recipes()) do print(r.id, r.power) end")
    @Note("Read-only. Your power points are in your body state every turn.")
    @SeeAlso("tlm.maid.list")
    public static List<Recipe> recipes(ServerCall call) {
        return Maids.altarRecipes(call.her());
    }
}
