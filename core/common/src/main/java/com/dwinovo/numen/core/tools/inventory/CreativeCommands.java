package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code creative}:创造模式才有的事——凭空取东西,原版创造物品栏的假体。
 *
 * <p>原版创造玩家有创造物品栏,假玩家没有界面,{@code give} 就是那个界面:补齐的是原版创造本来就有的能力(同伴能进创造已过
 * 主人的权限门)。生存画像如实拒绝,把她往采集、合成、交易的正道上引。当场完成,不占任务槽。
 */
public final class CreativeCommands {

    static final String GROUP = "creative";

    /** 一次最多一背包量级(36 格 × 64)。 */
    private static final int GIVE_MAX = 2304;

    private static final Param<ResourceLocation> ITEM = Param.required("item", ArgType.id(), "The item to conjure.");
    private static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, GIVE_MAX),
            "How many to take.")
            .whenOmitted("take one, like /give");

    private CreativeCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Creative mode only: conjuring items, like the creative menu.",
                CreativeCommands::actions);
    }

    private static void actions(CommandGroup creative) {
        creative.server("give", "Conjure items into your inventory, like the creative menu.",
                        CreativeCommands::give, ITEM, COUNT)
                .returns(ScriptType.table(ScriptType.field("took", ScriptType.INTEGER, null),
                        ScriptType.field("carrying", ScriptType.INTEGER, null)))
                .example("numen.creative.give(\"minecraft:diamond\", {count = 64})")
                .note("Fails in survival mode; there you mine, craft, loot or trade for items instead.")
                .note("What doesn't fit in your inventory drops at your feet.");
    }

    private static void give(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        String id = args.get(ITEM).toString();
        if (!WorkProfile.of(companion).freeMaterials()) {
            src.reply(TaskResult.fail(ErrorKind.DENIED, "survival mode can't conjure items — mine, craft, loot or "
                    + "trade for " + id + " instead (numen.creative.give works only in creative mode)", null).toJson());
            return;
        }
        Item item = ToolArgs.parseItem(id);
        int want = args.get(COUNT) == null ? 1 : Math.clamp(args.get(COUNT), 1, GIVE_MAX);
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
        int carrying = companion.getInventory().countItem(item);
        src.reply(TaskResult.ok("took " + want + " × " + id + " (now carrying " + carrying + ")",
                java.util.Map.of("took", want, "carrying", carrying)).toJson());
    }
}
