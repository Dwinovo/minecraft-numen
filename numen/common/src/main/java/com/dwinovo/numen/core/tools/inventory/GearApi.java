package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.gear.Wardrobe;
import com.dwinovo.numen.core.task.inventory.EquipTaskRecord;
import com.dwinovo.numen.core.task.inventory.UnequipTaskRecord;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code numen.gear}:穿戴与手里拿的——穿上、拿在手里、摘下来。都是有界短活(经 {@link ServerCall#sync}):东西只在背包和身上之间搬,
 * 不用、不倒、不扔,不改世界。槽名随身体而定(模组会加槽),是不是真有这个槽、穿不穿得上,都由 {@link Wardrobe} 在身上答。
 */
public final class GearApi {

    private GearApi() {}

    public static void install(NumenApi numen) {
        numen.api("gear", "What you wear and hold: putting it on, taking it in hand, taking it off.", GearApi.class);
    }

    /** 穿哪一件、穿在哪。 */
    public record Wear(@Doc("The item to put on; it must be in your backpack.") Item item,
                       @Doc("Where to put it: a slot name listed in <worn>.")
                       @Omitted("choose automatically: a free slot that takes it (swapping out what was there)")
                       Optional<String> slot) {}

    /**
     * {@code armor} 是卸下专用的别名,穿戴没有这个目标——四件甲各回各槽;两只手不是穿戴位置,归 {@code hold}。
     */
    @Fn("Wear an item from your backpack: armor, or an accessory a mod adds slots for.")
    @Example("numen.gear.wear(\"minecraft:iron_helmet\")")
    @Example("numen.gear.wear(\"minecraft:iron_helmet\", {slot = \"head\"})")
    @Note("Your wearable slots and what is on them are listed in <worn>. A tool or anything else you hold is "
            + "numen.gear.hold.")
    @Note("Whatever it swaps out goes back into your backpack; nothing is dropped. It only moves the item: nothing is "
            + "used, poured or thrown.")
    @Note("Fails without changing anything when the slot refuses the item or your backpack has no room for what comes "
            + "off.")
    @SeeAlso({"numen.gear.hold", "numen.gear.remove"})
    public static Pending<Wardrobe.Change> wear(ServerCall call, Wear args) {
        String slot = args.slot().map(s -> s.toLowerCase(Locale.ROOT)).orElse(null);
        String id = BuiltInRegistries.ITEM.getKey(args.item()).toString();
        if (Wardrobe.ARMOR.equals(slot)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "slot = \"armor\" is only for numen.gear.remove (it means all "
                    + "four armor pieces)", Call.of("numen.gear.wear", id));
        }
        if (Wardrobe.MAINHAND.equals(slot) || Wardrobe.OFFHAND.equals(slot)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a hand is not a slot you wear: numen.gear.hold takes it in hand",
                    Wardrobe.OFFHAND.equals(slot) ? Call.of("numen.gear.hold", id, Map.of("hand", "off"))
                            : Call.of("numen.gear.hold", id));
        }
        return call.sync(new EquipTaskRecord(call, args.item(), slot, BuiltInRegistries.ITEM.getKey(args.item()).getPath()));
    }

    /** 哪只手。 */
    public enum Hand { MAIN, OFF }

    /** 拿哪一件、哪只手。 */
    public record Hold(@Doc("The item to hold; it must be in your backpack.") Item item,
                       @Doc("Which hand.")
                       @Omitted("the hand Minecraft puts it in: a shield to the off hand, anything else to the main hand")
                       Optional<Hand> hand) {}

    /** 不写哪只手时照原版放东西的规矩(盾这类副手物进副手,其余进主手),同 {@link Wardrobe} 里那一条。 */
    @Fn("Take an item from your backpack into your hand.")
    @Example("numen.gear.hold(\"minecraft:iron_pickaxe\")")
    @Example("numen.gear.hold(\"minecraft:torch\", {hand = \"off\"})")
    @Note("It only moves the item: nothing is used, poured or thrown, so a water bucket stays full.")
    @Note("The main hand is the hotbar slot you hold, so nothing comes out of it; what the off hand held goes back "
            + "into your backpack, and with no room for it nothing changes.")
    @SeeAlso({"numen.gear.wear", "numen.gear.remove"})
    public static Pending<Wardrobe.Change> hold(ServerCall call, Hold args) {
        Item item = args.item();
        String slot = args.hand().map(h -> h == Hand.OFF ? Wardrobe.OFFHAND : Wardrobe.MAINHAND)
                .orElseGet(() -> Wardrobe.handFor(call.her(), new ItemStack(item)));
        return call.sync(new EquipTaskRecord(call, item, slot, BuiltInRegistries.ITEM.getKey(item).getPath()));
    }

    /** 摘什么。 */
    public record Remove(@Doc("Which slot to empty: mainhand, offhand, a slot name listed in <worn>, or armor for all "
            + "four armor pieces.")
                         @Omitted("go by the item option; with no item either, take off all four armor pieces")
                         Optional<String> slot,
                         @Doc("Take off the piece you wear that is this item.")
                         @Omitted("take off whatever the slot option holds") Optional<Item> item) {}

    @Fn("Take gear off back into your backpack.")
    @Example("numen.gear.remove()")
    @Example("numen.gear.remove({slot = \"offhand\"})")
    @Example("numen.gear.remove({item = \"minecraft:iron_helmet\"})")
    @Note("Give slot, item, or both (then only that item in those slots); neither takes off all four armor pieces.")
    @Note("A piece that doesn't fit in your backpack, or refuses to come off (curse of binding), stays on and the "
            + "result says so.")
    @SeeAlso({"numen.gear.wear", "numen.gear.hold"})
    public static Pending<Wardrobe.Change> remove(ServerCall call, Remove args) {
        Item item = args.item().orElse(null);
        String slot = args.slot().map(s -> s.toLowerCase(Locale.ROOT))
                .orElse(item == null ? Wardrobe.ARMOR : null);
        String label = slot != null ? slot : BuiltInRegistries.ITEM.getKey(item).getPath();
        return call.sync(new UnequipTaskRecord(call, slot, item, label));
    }
}
