package com.dwinovo.numen.api.agent.request;


import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.agent.scope.Handle;
import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 挂进请求的那块背包长什么样。它是模型每一轮都会读的东西——数错了、漏了主手,她会照着
 * 错的事实决定下一步,而且不会有任何报错。需要 MC 注册表。
 *
 * <p>引导 MC 时 Brigadier 的内置报错被换成了 MC 的说法;测完换回引导之前的那一份,同一个 JVM 里读 Brigadier
 * 原话的命令测试不随这个类先跑还是后跑而变(与 {@code BrigadierHelpTest} 同一个做法)。
 */
@Tag("mc")
class InventoryBlockTest {

    private static boolean booted;
    private static BuiltInExceptionProvider brigadierWords;

    @BeforeAll
    static void boot() {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    @AfterAll
    static void restoreBrigadierWords() {
        CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }

    private static BodySnapshot snapshot(int selected, ItemStack offhand,
                                                          ItemStack... items) {
        return new BodySnapshot(true, List.of(items), List.of(), 20, 5f,
                selected, offhand, List.of(), "", "", 1L, Nearby.NONE);
    }

    /** 这份快照的背包块。问回答者时没有身体可给:这里登记的回答者都不读它。 */
    private static String inventory(BodySnapshot snapshot) {
        return RuntimeState.renderInventory(snapshot, null);
    }

    // ==================== 碰着水的 ====================

    /** 水况片段挂在 {@code <runtime_state>} 里:在水里有这一段,不在水里一个字都没有。 */
    /** 请求里有"现在是游戏里几点",和事件、主人的话上的日期钟点是同一种写法。 */
    @Test
    void theRuntimeStateSaysWhatTimeItIsInTheGame() {
        assumeTrue(booted);
        assertTrue(new RuntimeState(java.util.UUID.randomUUID(), () -> null, () -> null, () -> null, () -> 3 * 24000L + 15250L).xml()
                .contains("<now day=\"3\" t=\"21:15\"/>"));
    }

    /** 眼前的主人在游戏里时,请求里写着他游戏里选的语言;他不在游戏里就没有这一行。 */
    @Test
    void theRuntimeStateNamesTheLanguageOfTheOwnersGame() {
        assumeTrue(booted);
        java.util.UUID her = java.util.UUID.randomUUID();
        assertTrue(new RuntimeState(her, () -> null, () -> null, () -> "Chinese (China)", () -> 0L).xml()
                .contains("<owner language=\"Chinese (China)\"/>"));
        assertFalse(new RuntimeState(her, () -> null, () -> null, () -> null, () -> 0L).xml().contains("<owner"));
    }

    @Test
    void theWaterSegmentIsThereOnlyWhileSheTouchesWater() {
        assumeTrue(booted);
        java.util.UUID her = java.util.UUID.randomUUID();
        BodySnapshot wet = new BodySnapshot(true, List.of(), List.of(), 20, 5f, 0, ItemStack.EMPTY, List.of(), "",
                com.dwinovo.numen.api.entity.WaterState.render(true, true, 240, 300, ""), 1L, Nearby.NONE);
        assertTrue(new RuntimeState(her, () -> null, () -> wet, () -> null, () -> 0L).xml()
                .contains("<water>In water, head under water; air left 12s.</water>"));
        BodySnapshot dry = new BodySnapshot(true, List.of(), List.of(), 20, 5f, 0, ItemStack.EMPTY, List.of(), "",
                com.dwinovo.numen.api.entity.WaterState.render(false, false, 300, 300, ""), 1L, Nearby.NONE);
        assertFalse(new RuntimeState(her, () -> null, () -> dry, () -> null, () -> 0L).xml().contains("water"));
    }

    // ==================== 身上在生效的 ====================

    private static BodySnapshot withEffects(long receivedAtMs,
            net.minecraft.world.effect.MobEffectInstance... effects) {
        return new BodySnapshot(true, List.of(), List.of(), 20, 5f,
                0, ItemStack.EMPTY, List.of(effects), "", "", receivedAtMs, Nearby.NONE);
    }

    /** 原版 UI 的口径:内部 amplifier 0 显示为 I,所以一级不写数字、二级写 2。 */
    @Test
    void effectsCarryTheirLevelTheWayVanillaShowsIt() {
        assumeTrue(booted);
        String block = RuntimeState.renderEffects(withEffects(0L,
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON, 200, 0),
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 400, 1)), 0L);
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
        String block = RuntimeState.renderEffects(withEffects(0L,
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON, 200, 0)), 5_000L);
        assertTrue(block.contains("poison (5s left)"), block);
    }

    /** 收到之后已经走完的,一个字都不该印。 */
    @Test
    void effectsThatRanOutSinceTheSnapshotAreDropped() {
        assumeTrue(booted);
        String block = RuntimeState.renderEffects(withEffects(0L,
                new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON, 200, 0)), 60_000L);
        assertEquals("", block);
    }

    // ==================== 同名的几种 ====================

    /** 没人有话说的东西,id 后面一个字都不该多出来。 */
    @Test
    void ordinaryItemsGainNoSuffix() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.DIRT)));
        assertTrue(block.contains("minecraft:dirt x1"), block);
        assertFalse(block.contains("minecraft:dirt["), block);
    }

    /** 同一种物品里装着钻石的潜影盒与空盒是两行,有回答者说话的那一行带着它说的;模型选得出要哪一个。 */
    @Test
    void sameItemWithDifferentContentsIsListedApart() {
        assumeTrue(booted);
        ItemStack full = new ItemStack(Items.SHULKER_BOX);
        full.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(
                        List.of(new ItemStack(Items.DIAMOND, 3))));
        // 这张表是全进程共用的:登记的回答者不论成败都撤掉
        Handle answerer = Questions.ITEM.register("gt_holds", (her, stack) -> {
            long stacks = stack.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.EMPTY).nonEmptyStream().count();
            return stacks == 0 ? null : Map.of("stacks", stacks);
        });
        try {
            String block = inventory(snapshot(0, ItemStack.EMPTY,
                    new ItemStack(Items.SHULKER_BOX), full));
            assertTrue(block.contains("carrying=minecraft:shulker_box x1, "
                    + "minecraft:shulker_box[gt_holds={stacks=1}] x1"), block);
        } finally {
            answerer.close();
        }
    }

    /** 两种不一样的叠没有回答者说得出区别:写出来一样,清单里是一行,件数加在一起。 */
    @Test
    void kindsNobodyTellsApartAreOneLineWithTheirCountsTogether() {
        assumeTrue(booted);
        ItemStack a = new ItemStack(Items.STICK);
        a.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData
                .of(new net.minecraft.nbt.CompoundTag()));
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("k", "b");
        ItemStack b = new ItemStack(Items.STICK, 2);
        b.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        String block = inventory(snapshot(0, ItemStack.EMPTY, a, b));
        assertTrue(block.contains("carrying=minecraft:stick x3\n"), block);
    }

    // ==================== 手上拿的 ====================

    @Test
    void theMainHandIsWhicheverSlotIsSelected() {
        assumeTrue(booted);
        String block = inventory(snapshot(1, ItemStack.EMPTY,
                new ItemStack(Items.DIRT), new ItemStack(Items.IRON_PICKAXE)));
        assertTrue(block.contains("main minecraft:iron_pickaxe"), block);
    }

    /** 空快照的 selectedSlot 会是 0 而 items 是空的——越界读不能炸。 */
    @Test
    void aSelectedSlotWithNothingBehindItReadsAsEmpty() {
        assumeTrue(booted);
        String block = inventory(snapshot(4, ItemStack.EMPTY));
        assertTrue(block.contains("main (empty)"), block);
    }

    @Test
    void anEmptyOffHandSaysSoRatherThanGoingMissing() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, ItemStack.EMPTY,
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
        String block = inventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.FURNACE, 64)));
        assertTrue(block.contains("carrying=minecraft:furnace x64"), block);
        assertTrue(block.contains("holding (already counted above)=main minecraft:furnace"), block);
        // 手上那份不能再带一次数量,否则它读起来就是另一堆
        assertEquals(1, block.split("x64", -1).length - 1, block);
    }

    @Test
    void theOffHandStackIsAlsoInTheTotalsAndNotRepeated() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, new ItemStack(Items.TORCH, 12),
                new ItemStack(Items.DIRT, 3)));
        assertTrue(block.contains("off minecraft:torch"), block);
        assertFalse(block.contains("x12"), block);   // 副手是装备槽,不在 36 格总数里,也不另报数量
    }

    // ==================== 计数 ====================

    /** 同一种东西散在几个格子里,模型要的是总数,不是"三堆石头"。 */
    @Test
    void thesameItemInSeveralSlotsIsOneTotal() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 22)));
        assertTrue(block.contains("minecraft:cobblestone x150"), block);
    }

    @Test
    void emptySlotsAreNotListed() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.DIRT, 3), ItemStack.EMPTY, ItemStack.EMPTY));
        assertTrue(block.contains("carrying=minecraft:dirt x3"), block);
        assertFalse(block.contains("air"), block);
    }

    /** 空背包得明说,否则 "carrying=" 后面一片空白读起来像渲染坏了。 */
    @Test
    void anEmptyBodySaysNothingOutLoud() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, ItemStack.EMPTY));
        assertTrue(block.contains("carrying=nothing"), block);
    }

    // ==================== 信封 ====================

    @Test
    void theBlockNamesItselfSoTheModelCanTellItApartFromToolOutput() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, ItemStack.EMPTY,
                new ItemStack(Items.DIRT)));
        assertTrue(block.startsWith("<inventory>"), block);
        assertTrue(block.endsWith("</inventory>"), block);
    }

    /** 这一句是它存在的理由:省掉一整轮 numen.status.self()。 */
    @Test
    void itTellsHerNotToRediscoverThisWithATool() {
        assumeTrue(booted);
        String block = inventory(snapshot(0, ItemStack.EMPTY));
        assertTrue(block.contains("numen.status.self()"), block);
    }

    // ==================== mainHand 本身 ====================

    @Test
    void mainHandIsBoundsCheckedOnTheSnapshot() {
        assumeTrue(booted);
        assertEquals(ItemStack.EMPTY,
                new BodySnapshot(true, List.of(), List.of(), 20, 5f,
                        3, ItemStack.EMPTY, List.of(), "", "", 1L, Nearby.NONE).mainHand());
        assertEquals(ItemStack.EMPTY,
                new BodySnapshot(true, List.of(), List.of(), 20, 5f,
                        -1, ItemStack.EMPTY, List.of(), "", "", 1L, Nearby.NONE).mainHand());
    }
}
