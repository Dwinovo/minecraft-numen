package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.client.data.ClientNumenState;

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
            com.dwinovo.numen.testutil.McTestComponents.bindAll();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    private static ClientNumenState.Snapshot snapshot(int selected, ItemStack offhand,
                                                          ItemStack... items) {
        return new ClientNumenState.Snapshot(true, List.of(items), List.of(), 20, 5f,
                selected, offhand, List.of(), "", -1, 1L);
    }

    // ==================== 身上在生效的 ====================

    private static ClientNumenState.Snapshot withEffects(long receivedAtMs,
            net.minecraft.world.effect.MobEffectInstance... effects) {
        return new ClientNumenState.Snapshot(true, List.of(), List.of(), 20, 5f,
                0, ItemStack.EMPTY, List.of(effects), "", -1, receivedAtMs);
    }

    /** 原版 UI 的口径:内部 amplifier 0 显示为 I,所以一级不写数字、二级写 2。 */
    @Test
    void effectsCarryTheirLevelTheWayVanillaShowsIt() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderEffects(withEffects(0L,
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON, 200, 0),
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.RESISTANCE, 400, 1)), 0L);
        assertTrue(block.contains("poison (10s left)"), block);
        assertTrue(block.contains("resistance 2 (20s left)"), block);
    }

    /**
     * 剩余时间按"收到至今"往下扣。服务端只在多了/少了/升级了时才重推,不扣的话她读到的
     * 秒数会停在收到那一刻——一个理直气壮的错数。
     */
    @Test
    void remainingTimeCountsDownFromWhenTheSnapshotArrived() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderEffects(withEffects(0L,
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON, 200, 0)), 5_000L);
        assertTrue(block.contains("poison (5s left)"), block);
    }

    /** 收到之后已经走完的,一个字都不该印。 */
    @Test
    void effectsThatRanOutSinceTheSnapshotAreDropped() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderEffects(withEffects(0L,
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON, 200, 0)), 60_000L);
        assertEquals("", block);
    }

    // ==================== 瓶子里装的是什么 ====================

    /** 三瓶不同的药水不能长成同一行 —— item id 全是 minecraft:potion,内容在组件里。 */
    @Test
    void potionsAreToldApartByWhatIsInThem() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                potion(net.minecraft.world.item.alchemy.Potions.HEALING),
                potion(net.minecraft.world.item.alchemy.Potions.POISON)));
        assertTrue(block.contains("minecraft:potion[healing]"), block);
        assertTrue(block.contains("minecraft:potion[poison]"), block);
    }

    /** 强化/延长是不同的药水,不能被抹平成同一个名字。 */
    @Test
    void strengthAndDurationVariantsKeepTheirOwnNames() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                potion(net.minecraft.world.item.alchemy.Potions.STRONG_HEALING)));
        assertTrue(block.contains("minecraft:potion[strong_healing]"), block);
    }

    /** 不带药水组件的东西一个字都不该多出来。 */
    @Test
    void ordinaryItemsGainNoSuffix() {
        assumeTrue(booted);
        String block = EntityAgentLoop.renderInventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.DIRT)));
        assertTrue(block.contains("minecraft:dirt x1"), block);
        assertFalse(block.contains("minecraft:dirt["), block);
    }

    private static ItemStack potion(
            net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        ItemStack stack = new ItemStack(Items.POTION);
        stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(potion));
        return stack;
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
                new ClientNumenState.Snapshot(true, List.of(), List.of(), 20, 5f,
                        3, ItemStack.EMPTY, List.of(), "", -1, 1L).mainHand());
        assertEquals(ItemStack.EMPTY,
                new ClientNumenState.Snapshot(true, List.of(), List.of(), 20, 5f,
                        -1, ItemStack.EMPTY, List.of(), "", -1, 1L).mainHand());
    }
}
