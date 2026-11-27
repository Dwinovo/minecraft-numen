package com.dwinovo.numen.plugins.minecraft.tools.inventory;

import com.dwinovo.numen.plugins.minecraft.NumenApiFixture;
import com.dwinovo.numen.api.sdk.ApiTester;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 三个查询函数 {@code minecraft.inv.recipes|uses|info} 只答游戏自己知道的:{@code info} 交回物品事实、没有说明文字;物品写错是参数错误。
 * 从她的入口跑;这里没有身体,配方要她的背包,所以只钉不要身体的那几条。
 */
@Tag("mc")
class InvLookupTest {

    private static boolean booted;

    @BeforeAll
    static void install() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
        if (booted) {
            NumenApiFixture.install();
        }
    }

    @Test
    void infoGivesTheGamesFactsAndNoNotes() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过查询函数钉桩");
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), "return minecraft.inv.info(\"minecraft:iron_pickaxe\")");
        assertTrue(run.ok(), run.message());
        JsonObject result = run.returned().getAsJsonObject();
        assertEquals("minecraft:iron_pickaxe", result.getAsJsonObject("facts").get("item").getAsString());
        assertEquals(0, result.getAsJsonArray("notes").size(), result.toString());
    }

    @Test
    void aWrongItemIsAnArgumentError() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过查询函数钉桩");
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), "return minecraft.inv.recipes(\"minecraft:no_such_item\")");

        assertFalse(run.ok());
        assertTrue(run.message().contains("bad_argument") && run.message().contains("there is no item minecraft:no_such_item"),
                run.message());
    }
}
