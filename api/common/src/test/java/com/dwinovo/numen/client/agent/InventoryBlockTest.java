package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.client.data.ClientNumenInventory;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 挂进请求的那块背包长什么样。它是模型每一轮都会读的东西——数错了、漏了主手,她会照着
 * 错的事实决定下一步,而且不会有任何报错。需要 MC 注册表。
 */
@Tag("mc")
class InventoryBlockTest {

    private static boolean booted;

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

    private static ClientNumenInventory.Snapshot snapshot(int selected, ItemStack offhand,
                                                          ItemStack... items) {
        return new ClientNumenInventory.Snapshot(true, List.of(items), List.of(), 20, 5f,
                selected, offhand, 1L);
    }

    // ==================== 手上拿的 ====================

    @Test
    void theMainHandIsWhicheverSlotIsSelected() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(1, ItemStack.EMPTY,
                new ItemStack(Items.DIRT), new ItemStack(Items.IRON_PICKAXE)));
        assertTrue(block.contains("main minecraft:iron_pickaxe"), block);
    }

    /** 空快照的 selectedSlot 会是 0 而 items 是空的——越界读不能炸。 */
    @Test
    void aSelectedSlotWithNothingBehindItReadsAsEmpty() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(4, ItemStack.EMPTY));
        assertTrue(block.contains("main (empty)"), block);
    }

    @Test
    void anEmptyOffHandSaysSoRatherThanGoingMissing() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.DIRT)));
        assertTrue(block.contains("off (empty)"), block);
    }

    /**
     * 手上那份不带数量,而且明说已经算进总数了。实测她见过 {@code main_hand=furnace x64}
     * 加 {@code carrying=furnace x64} 就当成两批、报成 128——总数只能有一处。
     */
    @Test
    void whatSheHoldsIsNeverCountedTwice() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.FURNACE, 64)));
        assertTrue(block.contains("carrying=minecraft:furnace x64"), block);
        assertTrue(block.contains("holding (already counted above)=main minecraft:furnace"), block);
        // 手上那份不能再带一次数量,否则它读起来就是另一堆
        assertEquals(1, block.split("x64", -1).length - 1, block);
    }

    @Test
    void theOffHandStackIsAlsoInTheTotalsAndNotRepeated() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, new ItemStack(Items.TORCH, 12),
                new ItemStack(Items.DIRT, 3)));
        assertTrue(block.contains("off minecraft:torch"), block);
        assertFalse(block.contains("x12"), block);   // 副手是装备槽,不在 36 格总数里,也不另报数量
    }

    // ==================== 计数 ====================

    /** 同一种东西散在几个格子里,模型要的是总数,不是"三堆石头"。 */
    @Test
    void thesameItemInSeveralSlotsIsOneTotal() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 22)));
        assertTrue(block.contains("minecraft:cobblestone x150"), block);
    }

    @Test
    void emptySlotsAreNotListed() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.DIRT, 3), ItemStack.EMPTY, ItemStack.EMPTY));
        assertTrue(block.contains("carrying=minecraft:dirt x3"), block);
        assertFalse(block.contains("air"), block);
    }

    /** 空背包得明说,否则 "carrying=" 后面一片空白读起来像渲染坏了。 */
    @Test
    void anEmptyBodySaysNothingOutLoud() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY));
        assertTrue(block.contains("carrying=nothing"), block);
    }

    // ==================== 信封 ====================

    @Test
    void theBlockNamesItselfSoTheModelCanTellItApartFromToolOutput() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.DIRT)));
        assertTrue(block.startsWith("<inventory>"), block);
        assertTrue(block.endsWith("</inventory>"), block);
    }

    /** 这一句是它存在的理由:省掉一整轮 get_self_status。 */
    @Test
    void itTellsHerNotToRediscoverThisWithATool() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY));
        assertTrue(block.contains("get_self_status"), block);
        assertTrue(block.contains("inspect_gui"), block);
    }

    // ==================== mainHand 本身 ====================

    @Test
    void mainHandIsBoundsCheckedOnTheSnapshot() {
        assumeTrue(booted);
        assertEquals(ItemStack.EMPTY,
                new ClientNumenInventory.Snapshot(true, List.of(), List.of(), 20, 5f,
                        3, ItemStack.EMPTY, 1L).mainHand());
        assertEquals(ItemStack.EMPTY,
                new ClientNumenInventory.Snapshot(true, List.of(), List.of(), 20, 5f,
                        -1, ItemStack.EMPTY, 1L).mainHand());
    }
}
