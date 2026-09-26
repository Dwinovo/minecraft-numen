package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.task.TaskDispatch;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code gear}:身上穿的、手里拿的——穿上 / 拿上,摘下来收回背包。
 *
 * <p>穿与脱是两个动作,一个动作一个意思。两个都只把参数翻译过来,穿戴位置、自动选位、拒绝的原因都由
 * {@link com.dwinovo.numen.core.gear.Wardrobe} 经装备位扩展点在身上答(原版四件甲与模组饰品栏同一扇门)。
 * 两个都是有界短活({@code runSync}):东西只在背包和身上之间搬,不用、不倒、不扔,不改世界。都不提升成快捷工具。
 *
 * <p>槽名随身体而定(模组会加槽,如 {@code curios:ring}),每轮随 {@code <worn>} 下发,不写进帮助——帮助是固定文字。
 */
public final class GearCommands {

    static final String GROUP = "gear";
    static final String WEAR = "wear";
    static final String REMOVE = "remove";

    private static final Param<ResourceLocation> WEAR_ITEM = Param.required("item", ArgType.id(),
            "The item to put on or hold; it must be in your backpack.");
    private static final Param<String> WEAR_SLOT = Param.optional("slot", ArgType.string(),
            "Where to put it.")
            .values("mainhand, offhand, or a slot name listed in <worn>")
            .whenOmitted("choose automatically: armor and accessories go to a free slot that takes them "
                    + "(swapping out what was there), a shield to the off hand, anything else to the main hand");
    private static final Param<String> REMOVE_SLOT = Param.optional("slot", ArgType.string(),
            "Which slot to empty.")
            .values("mainhand, offhand, a slot name listed in <worn>, or armor for all four armor pieces")
            .whenOmitted("go by --item");
    private static final Param<ResourceLocation> REMOVE_ITEM = Param.optional("item", ArgType.id(),
            "Take off the piece you wear that is this item.")
            .whenOmitted("take off whatever --slot holds");

    private static final InventoryOps INVENTORY = new InventoryOps();

    private GearCommands() {}

    static String line(String action) {
        return GROUP + " " + action;
    }

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "What you wear and hold: putting it on, taking it off.",
                GearCommands::actions);
    }

    private static void actions(CommandGroup gear) {
        gear.server(WEAR, "Wear or hold an item from your backpack.",
                GearCommands::wear, WEAR_ITEM, WEAR_SLOT)
                .example(line(WEAR) + " minecraft:iron_helmet")
                .example(line(WEAR) + " minecraft:shield --slot offhand")
                .note("Your wearable slots and what is on them are listed in <worn>.")
                .note("Whatever it swaps out goes back into your backpack; nothing is dropped. It only moves the "
                        + "item: nothing is used, poured or thrown.")
                .note("Fails without changing anything when the slot refuses the item or your backpack has no "
                        + "room for what comes off.")
                .seeAlso(line(REMOVE));
        gear.server(REMOVE, "Take gear off back into your backpack.",
                GearCommands::remove, REMOVE_SLOT, REMOVE_ITEM)
                .example(line(REMOVE) + " --slot armor")
                .example(line(REMOVE) + " --item minecraft:iron_helmet")
                .note("Give --slot, --item, or both (then only that item in those slots).")
                .note("A piece that doesn't fit in your backpack, or refuses to come off (curse of binding), "
                        + "stays on and the result says so.")
                .seeAlso(line(WEAR));
    }

    private static void wear(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(),
                INVENTORY.wear(src, args.get(WEAR_ITEM).toString(), args.get(WEAR_SLOT)), src::reply);
    }

    private static void remove(ServerSource src, CommandArgs args) {
        ResourceLocation item = args.get(REMOVE_ITEM);
        TaskDispatch.runSync(src.companion(),
                INVENTORY.remove(src, args.get(REMOVE_SLOT), item == null ? null : item.toString()), src::reply);
    }
}
