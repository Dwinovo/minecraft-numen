package com.dwinovo.numen.core.tools.work;

import java.util.List;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.pathing.settings.ThrowawayBlocks;
import com.dwinovo.numen.core.tools.ThrowawayOps;

import net.minecraft.resources.ResourceLocation;

/**
 * {@code throwaway}:她自己的一项设置——寻路往上垫柱、过沟搭桥时愿意消耗掉的方块(名字取 Baritone 的
 * acceptableThrowawayItems)。四个动作只改这份清单,不占身体,当场回,改完报主人一句。
 *
 * <p>没有"看清单"的动作:清单现状是她身体状态的一段({@link ThrowawayBlocks#bodyState}),和命令组同一处装上,每轮挂在
 * {@code <runtime_state>} 里,{@code status self} 也读得到。
 */
public final class ThrowawayCommands {

    static final String GROUP = "throwaway";

    private static final Param<List<ResourceLocation>> BLOCKS = Param.required("blocks", ArgType.list(ArgType.id()),
            "Block ids.");

    private static final ThrowawayOps OPS = new ThrowawayOps();

    private ThrowawayCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Your own setting: the blocks you are willing to spend when pathfinding "
                + "pillars up or bridges a gap. The current list is in your status as <throwaway>.",
                ThrowawayCommands::actions);
        numen.contributeBodyState(ThrowawayBlocks::bodyState);
    }

    private static void actions(CommandGroup throwaway) {
        throwaway.server("add", "Add blocks you are willing to spend.",
                        (src, args) -> src.reply(OPS.add(src.companion(), ids(args))), BLOCKS)
                .example("throwaway add minecraft:cobblestone minecraft:cobbled_deepslate")
                .note("Anything listed WILL be consumed and never comes back: list what is junk here and now. "
                        + "Cobblestone is junk in a mineshaft and precious in the End.")
                .note("Instant; your owner is told what you changed. The list persists across sessions and is used "
                        + "by every move you make, reflexes such as fleeing included.")
                .seeAlso("throwaway remove", "throwaway set");
        throwaway.server("remove", "Take blocks off the list.",
                        (src, args) -> src.reply(OPS.remove(src.companion(), ids(args))), BLOCKS)
                .example("throwaway remove minecraft:dirt")
                .note("Instant; your owner is told what you changed.")
                .seeAlso("throwaway add");
        throwaway.server("set", "Replace the whole list.",
                        (src, args) -> src.reply(OPS.set(src.companion(), ids(args))), BLOCKS)
                .example("throwaway set minecraft:netherrack")
                .note("Instant; your owner is told what you changed. To allow nothing at all, use `throwaway clear`.")
                .seeAlso("throwaway add", "throwaway clear");
        throwaway.server("clear", "Empty the list, so no block may be spent.",
                        (src, args) -> src.reply(OPS.clear(src.companion())))
                .example("throwaway clear")
                .note("A real choice for when what you carry is earmarked (the dirt is for a build): you then "
                        + "cannot pillar or bridge at all, and routes that need it fail until you add some back.")
                .note("Instant; your owner is told what you changed.")
                .seeAlso("throwaway add");
    }

    private static List<String> ids(CommandArgs args) {
        return args.get(BLOCKS).stream().map(ResourceLocation::toString).toList();
    }
}
