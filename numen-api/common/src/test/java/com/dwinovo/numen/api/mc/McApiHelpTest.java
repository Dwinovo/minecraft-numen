package com.dwinovo.numen.api.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 她写 {@code help}:走原版那一条,不管 {@code help} 这个名字是不是被服务端让给了别的帮助。 */
class McApiHelpTest {

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    @Test
    void helpStaysHelpWhereTheVanillaOneKeepsItsName() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(literal("help").executes(ctx -> 1));

        assertEquals("help", McApi.vanillaHelp(dispatcher, "help"));
        assertEquals("help give", McApi.vanillaHelp(dispatcher, "help give"));
    }

    @Test
    void helpGoesToTheVanillaAliasWhereTheNameWasTakenOver() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        LiteralCommandNode<CommandSourceStack> vanilla = dispatcher.register(literal("vanilla-help").executes(ctx -> 1));
        dispatcher.register(literal("help").executes(ctx -> 1));
        dispatcher.register(literal("minecraft:help").redirect(vanilla));

        assertEquals("minecraft:help", McApi.vanillaHelp(dispatcher, "help"));
        assertEquals("minecraft:help give", McApi.vanillaHelp(dispatcher, "help give"));
        assertEquals("give @s stone", McApi.vanillaHelp(dispatcher, "give @s stone"));
        assertEquals("helpful", McApi.vanillaHelp(dispatcher, "helpful"));
    }
}
