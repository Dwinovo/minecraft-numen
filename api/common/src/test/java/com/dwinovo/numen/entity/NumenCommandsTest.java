package com.dwinovo.numen.entity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;

import net.minecraft.commands.CommandSourceStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 权限命令的树形状:每条都解析到有执行体的节点,参数取得到;写错的表名、序号 0、少了请求号都解析不通。
 * 执行与鉴权(只认主人)要一台服务器,在 GameTest 里钉。
 */
@Tag("mc")
class NumenCommandsTest {

    private static boolean booted;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过命令树钉桩");
        dispatcher = new CommandDispatcher<>();
        NumenCommands.register(dispatcher);
    }

    /** 整行解析到底、落在有执行体的节点上,返回参数表。 */
    private Map<String, ParsedArgument<CommandSourceStack, ?>> runs(String command) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, null);
        assertFalse(parsed.getReader().canRead(), "not fully parsed: " + command + " " + parsed.getExceptions());
        CommandContextBuilder<CommandSourceStack> context = parsed.getContext();
        while (context.getChild() != null) {
            context = context.getChild();
        }
        assertNotNull(context.getCommand(), "no command at the end of: " + command);
        return context.getArguments();
    }

    private boolean fails(String command) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, null);
        CommandContextBuilder<CommandSourceStack> context = parsed.getContext();
        return parsed.getReader().canRead() || !parsed.getExceptions().isEmpty() || context.getCommand() == null;
    }

    @Test
    void modeShowsOrSets() {
        assertEquals("Aria", runs("numen permission mode Aria").get("name").getResult());
        runs("numen permission mode Aria ask");
        runs("numen permission mode Aria bypass");
        runs("numen permission mode Aria observe");
        assertTrue(fails("numen permission mode Aria chaos"));
    }

    @Test
    void rulesListAddRemoveReset() {
        runs("numen permission rules list");
        assertEquals("break(placed & minecraft:cobblestone)",
                runs("numen permission rules add allow break(placed & minecraft:cobblestone)").get("rule").getResult());
        runs("numen permission rules add deny attack(villager)");
        runs("numen permission rules add ask take(*)");
        assertEquals(2, runs("numen permission rules remove ask 2").get("row").getResult());
        runs("numen permission rules reset");
        assertTrue(fails("numen permission rules add maybe take(*)"), "only deny, ask and allow are tables");
        assertTrue(fails("numen permission rules remove ask 0"), "rows are numbered from 1");
    }

    @Test
    void consentTakesAnIdAndAnOptionalNote() {
        Map<String, ParsedArgument<CommandSourceStack, ?>> bare = runs("numen consent allow 42");
        assertEquals(42L, bare.get("id").getResult());
        Map<String, ParsedArgument<CommandSourceStack, ?>> noted = runs("numen consent deny 7 那是我的柱子 别动");
        assertEquals("那是我的柱子 别动", noted.get("note").getResult());
        runs("numen consent remember 9");
        assertTrue(fails("numen consent allow"), "a request id is required");
        assertTrue(fails("numen consent maybe 3"));
    }
}
