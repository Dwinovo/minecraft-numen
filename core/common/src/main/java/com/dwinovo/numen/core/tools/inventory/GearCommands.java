package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.agent.script.ScriptType;
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
 * {@code gear}:身上穿的、手里拿的——穿上、拿到手上,摘下来收回背包。
 *
 * <p>穿、拿、脱是三个动作,一个动作一个意思:穿戴位置归 {@code wear},两只手归 {@code hold}。都只把参数翻译过来,
 * 穿戴位置、自动选位、拒绝的原因都由
 * {@link com.dwinovo.numen.core.gear.Wardrobe} 经装备位扩展点在身上答(原版四件甲与模组饰品栏同一扇门)。
 * 都是有界短活({@code runSync}):东西只在背包和身上之间搬,不用、不倒、不扔,不改世界。都不提升成快捷工具。
 *
 * <p>槽名随身体而定(模组会加槽,如 {@code curios:ring}),每轮随 {@code <worn>} 下发,不写进帮助——帮助是固定文字。
 */
public final class GearCommands {

    static final String GROUP = "gear";
    static final String WEAR = "wear";
    static final String HOLD = "hold";
    static final String REMOVE = "remove";
    /** 四件甲一起:{@code --slot} 写它,两样都不写也是它。 */
    private static final String ARMOR = "armor";

    private static final Param<ResourceLocation> WEAR_ITEM = Param.required("item", ArgType.id(),
            "The item to put on; it must be in your backpack.");
    private static final Param<String> WEAR_SLOT = Param.optional("slot", ArgType.string(),
            "Where to put it.")
            .values("a slot name listed in <worn>")
            .whenOmitted("choose automatically: a free slot that takes it (swapping out what was there)");
    private static final Param<ResourceLocation> HOLD_ITEM = Param.required("item", ArgType.id(),
            "The item to hold; it must be in your backpack.");
    private static final Param<String> HAND = Param.optional("hand", ArgType.oneOf("main", "off"),
            "Which hand.")
            .whenOmitted("the hand Minecraft puts it in: a shield to the off hand, anything else to the main hand");
    private static final Param<String> REMOVE_SLOT = Param.optional("slot", ArgType.string(),
            "Which slot to empty.")
            .values("mainhand, offhand, a slot name listed in <worn>, or " + ARMOR + " for all four armor pieces")
            .whenOmitted("go by the item option; with no item either, take off all four armor pieces");
    private static final Param<ResourceLocation> REMOVE_ITEM = Param.optional("item", ArgType.id(),
            "Take off the piece you wear that is this item.")
            .whenOmitted("take off whatever the slot option holds");

    private static final InventoryOps INVENTORY = new InventoryOps();

    private GearCommands() {}

    static String line(String action) {
        return GROUP + " " + action;
    }

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "What you wear and hold: putting it on, taking it in hand, taking it off.",
                GearCommands::actions);
    }

    private static void actions(CommandGroup gear) {
        gear.server(WEAR, "Wear an item from your backpack: armor, or an accessory a mod adds slots for.",
                GearCommands::wear, WEAR_ITEM, WEAR_SLOT)
                .returns(ScriptType.table(ScriptType.optional("item", ScriptType.STRING, null),
                        ScriptType.optional("slot", ScriptType.STRING, null),
                        ScriptType.optional("removed", ScriptType.listOf(ScriptType.STRING), null),
                        ScriptType.optional("still_worn", ScriptType.listOf(ScriptType.STRING), null)))
                .example("numen.gear.wear(\"minecraft:iron_helmet\")")
                .example("numen.gear.wear(\"minecraft:iron_helmet\", {slot = \"head\"})")
                .note("Your wearable slots and what is on them are listed in <worn>. A tool or anything else you "
                        + "hold is numen.gear.hold.")
                .note("Whatever it swaps out goes back into your backpack; nothing is dropped. It only moves the "
                        + "item: nothing is used, poured or thrown.")
                .note("Fails without changing anything when the slot refuses the item or your backpack has no "
                        + "room for what comes off.")
                .seeAlso(line(HOLD), line(REMOVE));
        gear.server(HOLD, "Take an item from your backpack into your hand.",
                GearCommands::hold, HOLD_ITEM, HAND)
                .returns(ScriptType.table(ScriptType.optional("item", ScriptType.STRING, null),
                        ScriptType.optional("slot", ScriptType.STRING, null)))
                .example("numen.gear.hold(\"minecraft:iron_pickaxe\")")
                .example("numen.gear.hold(\"minecraft:torch\", {hand = \"off\"})")
                .note("It only moves the item: nothing is used, poured or thrown, so a water bucket stays full.")
                .note("The main hand is the hotbar slot you hold, so nothing comes out of it; what the off hand held "
                        + "goes back into your backpack, and with no room for it nothing changes.")
                .seeAlso(line(WEAR), line(REMOVE));
        gear.server(REMOVE, "Take gear off back into your backpack.",
                GearCommands::remove, REMOVE_SLOT, REMOVE_ITEM)
                .returns(ScriptType.table(ScriptType.optional("item", ScriptType.STRING, null),
                        ScriptType.optional("slot", ScriptType.STRING, null),
                        ScriptType.optional("removed", ScriptType.listOf(ScriptType.STRING), null),
                        ScriptType.optional("still_worn", ScriptType.listOf(ScriptType.STRING), null)))
                .example("numen.gear.remove()")
                .example("numen.gear.remove({slot = \"offhand\"})")
                .example("numen.gear.remove({item = \"minecraft:iron_helmet\"})")
                .note("Give slot, item, or both (then only that item in those slots); neither takes off all four "
                        + "armor pieces.")
                .note("A piece that doesn't fit in your backpack, or refuses to come off (curse of binding), "
                        + "stays on and the result says so.")
                .seeAlso(line(WEAR), line(HOLD));
    }

    private static void wear(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(),
                INVENTORY.wear(src, args.get(WEAR_ITEM).toString(), args.get(WEAR_SLOT)), src::reply);
    }

    private static void hold(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(),
                INVENTORY.hold(src, args.get(HOLD_ITEM).toString(), args.get(HAND)), src::reply);
    }

    private static void remove(ServerSource src, CommandArgs args) {
        ResourceLocation item = args.get(REMOVE_ITEM);
        String slot = args.get(REMOVE_SLOT) == null && item == null ? ARMOR : args.get(REMOVE_SLOT);
        TaskDispatch.runSync(src.companion(),
                INVENTORY.remove(src, slot, item == null ? null : item.toString()), src::reply);
    }
}
