package com.dwinovo.numen.api.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 她要的帮助是指令树对某条指令的说明,直接从调度器读:树上有没有叫 {@code help} 的指令、它是谁的,都不相干。 */
class McApiHelpTest {

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    /** 一棵没有任何 {@code help} 的树:{@code give <count>},{@code time} 带两个子指令。 */
    private static CommandDispatcher<CommandSourceStack> tree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(literal("give").then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("count",
                IntegerArgumentType.integer(1)).executes(ctx -> 1)));
        dispatcher.register(literal("time").then(literal("query").executes(ctx -> 1)).then(literal("set").executes(ctx -> 1)));
        return dispatcher;
    }

    @Test
    void withoutACommandItListsTheUsageOfEveryCommandOnTheTree() {
        List<String> usage = BrigadierHelp.usage(tree(), "", null);

        assertEquals(List.of("/give <count>", "/time (query|set)"), usage);
    }

    @Test
    void withACommandItGivesTheUsageBelowTheWrittenPart() {
        assertEquals(List.of("/give <count>"), BrigadierHelp.usage(tree(), "give", null));
        assertEquals(List.of("/time query", "/time set"), BrigadierHelp.usage(tree(), "time", null));
    }

    @Test
    void aCommandTheTreeDoesNotHaveIsNoHelp() {
        assertNull(BrigadierHelp.usage(tree(), "nosuchcommand", null));
    }
}
