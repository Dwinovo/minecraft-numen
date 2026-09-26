package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.core.tools.QueryExtraOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code inv}:背包里的东西——合成、查配方、吃、丢,以及创造模式里凭空取。
 *
 * <p>都在服务端执行,都不提升成快捷工具。合成、查配方、取物当场回;丢是有界短活({@code runSync}),每次都过
 * 权限层,可能等主人答复;吃是长活——咀嚼要时间,受理即回执,吃完发 {@code task_finished}。
 */
public final class InvCommands {

    static final String GROUP = "inv";
    static final String CRAFT = "craft";
    static final String RECIPE = "recipe";
    static final String EAT = "eat";
    static final String DROP = "drop";
    static final String TAKE = "take";

    /** 创造取物一次最多一背包量级(36 格 × 64)。 */
    private static final int TAKE_MAX = 2304;

    private static final Param<ResourceLocation> CRAFT_ITEM = Param.required("item", ArgType.id(),
            "The item to craft.");
    private static final Param<Integer> CRAFT_COUNT = Param.optional("count", ArgType.integer(1, 256),
            "How many of the item you want.")
            .whenOmitted("craft one");
    private static final Param<ResourceLocation> RECIPE_ITEM = Param.required("item", ArgType.id(),
            "The item you want to make.");
    private static final Param<ResourceLocation> FOOD = Param.required("item", ArgType.id(),
            "The food or drink to consume.")
            .values("something you carry");
    private static final Param<ResourceLocation> DROP_ITEM = Param.required("item", ArgType.id(),
            "The item to drop.");
    private static final Param<Integer> DROP_COUNT = Param.required("count", ArgType.integer(1, 999),
            "How many to drop; more than you carry drops all you have of it.");
    private static final Param<ResourceLocation> TAKE_ITEM = Param.required("item", ArgType.id(),
            "The item to conjure.");
    private static final Param<Integer> TAKE_COUNT = Param.required("count", ArgType.integer(1, TAKE_MAX),
            "How many to take.");

    private static final CraftOps CRAFTING = new CraftOps();
    private static final QueryExtraOps RECIPES = new QueryExtraOps();
    private static final InventoryOps INVENTORY = new InventoryOps();

    private InvCommands() {}

    /** 回执与别的命令的帮助里提到这一组的动作时写的那一行。 */
    static String line(String action) {
        return GROUP + " " + action;
    }

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Your inventory: crafting, recipes, eating, dropping, conjuring in creative.",
                InvCommands::actions);
    }

    private static void actions(CommandGroup inv) {
        inv.server(CRAFT, "Craft an item from your inventory in one go: finds the recipe, lays it into a real "
                        + "crafting grid, takes the result.",
                InvCommands::craft, CRAFT_ITEM, CRAFT_COUNT)
                .example(line(CRAFT) + " minecraft:iron_pickaxe")
                .example(line(CRAFT) + " oak_planks --count 8")
                .note("2x2 recipes work anywhere; a 3x3 recipe needs a crafting table within reach (~4 blocks). "
                        + "The result says where the nearest one is, or that you should place one (a "
                        + "crafting_table is 4 planks, 2x2).")
                .note("Missing materials are reported with exact shortfalls: get those first, then run it again. "
                        + "It stops early, and says so, when materials run out or the inventory fills.")
                .note("Only [crafting] recipes. Smelting, stonecutting and smithing happen at the station: "
                        + "use block it, use gui, then transfer.")
                .seeAlso(line(RECIPE));
        inv.server(RECIPE, "How an item is made, like JEI: every recipe that outputs it, at every station.",
                InvCommands::recipe, RECIPE_ITEM)
                .example(line(RECIPE) + " minecraft:diamond_pickaxe")
                .note("Instant and read-only.")
                .note("Each recipe is tagged [crafting], [smelting], [stonecutter], [smithing] …: [crafting] is "
                        + line(CRAFT) + "; the others are made at their station (use block it, use gui, transfer).")
                .note("No recipe found means the item is mined or traded, not made.")
                .seeAlso(line(CRAFT));
        inv.server(EAT, "Eat or drink something from your inventory.",
                InvCommands::eat, FOOD)
                .example(line(EAT) + " minecraft:cooked_beef")
                .note("Background work: returns at once, and the result arrives as a task_finished event.")
                .note("A real timed action: only when the chewing finishes do hunger, saturation and the item's "
                        + "effects (a golden apple's absorption) apply. Health then regenerates from saturation, "
                        + "the same as a real player's.")
                .note("Fails, keeping the food, when you don't carry it, it isn't food or drink, or you are "
                        + "already full.");
        inv.server(DROP, "Drop items from your inventory on the ground in front of you.",
                InvCommands::drop, DROP_ITEM, DROP_COUNT)
                .example(line(DROP) + " minecraft:cobblestone 32")
                .note("Asks your owner first unless their rules allow it; the call waits for the answer.")
                .note("Dropped items despawn after 5 minutes. To store things, open a chest with use block and "
                        + "transfer them into it instead.")
                .note("Returns how many were dropped and how many remain.")
                .seeAlso("use block");
        inv.server(TAKE, "Creative mode only: conjure items into your inventory, like the creative menu.",
                InvCommands::take, TAKE_ITEM, TAKE_COUNT)
                .example(line(TAKE) + " minecraft:diamond 64")
                .note("Fails in survival mode; there you mine, craft, loot or trade for items instead.")
                .note("What doesn't fit in your inventory drops at your feet.");
    }

    private static void craft(ServerSource src, CommandArgs args) {
        src.reply(CRAFTING.craft(args.get(CRAFT_ITEM).toString(), args.get(CRAFT_COUNT), src.companion()));
    }

    private static void recipe(ServerSource src, CommandArgs args) {
        src.reply(RECIPES.lookupRecipe(args.get(RECIPE_ITEM).toString(), src.companion()));
    }

    /** 长活:咀嚼要时间,一口一口吃到饱可能更久,占着一轮对话不合理;受理即回执,吃完发 task_finished。 */
    private static void eat(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, INVENTORY.eatItem(src, args.get(FOOD).toString()));
    }

    /** 有界短活:丢东西每次都过权限层,可能挂着等主人。 */
    private static void drop(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(),
                INVENTORY.dropItems(src, args.get(DROP_ITEM).toString(), args.get(DROP_COUNT)), src::reply);
    }

    /**
     * 创造画像专属:原版创造玩家有创造物品栏,假玩家没有界面,这个动作就是那个界面的假体,补齐的是原版创造本来就有的
     * 能力(同伴能进创造已过主人的权限门)。生存画像如实拒绝,把她往采集、合成、交易的正道上引。当场完成,不占任务槽。
     */
    private static void take(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        String id = args.get(TAKE_ITEM).toString();
        if (!WorkProfile.of(companion).freeMaterials()) {
            src.reply(TaskResult.fail("survival mode can't conjure items — mine, craft, loot or trade for " + id
                    + " instead (" + line(TAKE) + " works only in creative mode)").toJson());
            return;
        }
        Item item = ToolArgs.parseItem(id);
        int want = Math.clamp(args.get(TAKE_COUNT), 1, TAKE_MAX);
        // 按满栈分批塞;背包塞不下的原版 add 会留在栈里,掉在脚下
        int remaining = want;
        while (remaining > 0) {
            int n = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, n);
            if (!companion.getInventory().add(stack) && !stack.isEmpty()) {
                companion.drop(stack, false);
            }
            remaining -= n;
        }
        src.reply(TaskResult.ok("took " + want + " × " + id + " (now carrying "
                + companion.getInventory().countItem(item) + ")").toJson());
    }
}
