package com.dwinovo.numen.core.pathing.bridge;

import com.dwinovo.numen.core.pathing.moves.ToolSet;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 冻结上下文的线程安全冒烟:ToolSet 构造时快照快捷栏,构造后
 * (1)另一线程读结果与主线程一致且不炸;(2)改写传入的原数组不影响
 * 已构造实例(防御性拷贝)。需要 MC 注册表(ItemStack/Block),
 * 无法引导的环境按假设跳过,不判失败。
 */
@Tag("mc")
class FrozenToolSetThreadingTest {

    private static boolean booted;

    @BeforeAll
    static void bootMinecraft() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;   // 环境无法引导——跳过,不判失败
        }
    }

    /**
     * 探针用剪刀×蜘蛛网:剪刀的挖掘速度规则直接引用方块(非 tag),
     * headless 引导下 tag 未绑定、镐类速度全部退化为 1,只有这类
     * 直引用工具能在测试环境里区分槽位。
     */
    private static ItemStack[] hotbarWithShears() {
        ItemStack[] hotbar = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            hotbar[i] = ItemStack.EMPTY;
        }
        hotbar[3] = new ItemStack(Items.SHEARS);
        return hotbar;
    }

    @Test
    void workerThreadReadsMatchMainThread() throws Exception {
        assumeTrue(booted, "MC 未能引导,跳过");
        ItemStack[] hotbar = hotbarWithShears();
        ToolSet frozen = new ToolSet(hotbar, 0, false, 1.0);

        // 主线程基准(独立实例,避免与 worker 共享逐块缓存)
        ToolSet reference = new ToolSet(hotbarWithShears(), 0, false, 1.0);
        double mainStr = reference.getStrVsBlock(Blocks.COBWEB.defaultBlockState());
        int mainSlot = reference.getBestSlot(Blocks.COBWEB, false);

        // 构造后由另一线程独占消费(构造主线程、消费 worker 的真实用法)
        CompletableFuture<double[]> off = CompletableFuture.supplyAsync(() -> new double[]{
                frozen.getStrVsBlock(Blocks.COBWEB.defaultBlockState()),
                frozen.getBestSlot(Blocks.COBWEB, false),
                frozen.getStrVsBlock(Blocks.DIRT.defaultBlockState()),
        });
        double[] results = off.get(10, TimeUnit.SECONDS);

        assertEquals(mainStr, results[0], 1e-12, "worker 线程读到的挖掘速度与主线程一致");
        assertEquals(mainSlot, (int) results[1], "worker 线程选出的槽位与主线程一致");
        assertTrue(results[2] > 0, "泥土可徒手破坏,速度为正");
        assertEquals(3, mainSlot, "蜘蛛网的最优工具是 3 号槽的剪刀");
    }

    @Test
    void mutatingSourceArrayAfterConstructionHasNoEffect() {
        assumeTrue(booted, "MC 未能引导,跳过");
        ItemStack[] hotbar = hotbarWithShears();
        ToolSet frozen = new ToolSet(hotbar, 0, false, 1.0);
        int before = frozen.getBestSlot(Blocks.COBWEB, false);
        assertEquals(3, before, "构造时剪刀在 3 号槽");

        // 构造后把原数组的剪刀挪到别的槽:快照不得跟着变
        hotbar[3] = ItemStack.EMPTY;
        hotbar[7] = new ItemStack(Items.SHEARS);
        int after = frozen.getBestSlot(Blocks.COBWEB, false);
        assertEquals(before, after, "构造后的外部改写不影响快照");

        // 探针本身可区分:用改写后的数组新建实例,选出的是新槽位
        ToolSet rebuilt = new ToolSet(hotbar, 0, false, 1.0);
        assertEquals(7, rebuilt.getBestSlot(Blocks.COBWEB, false), "新实例看到挪动后的剪刀");
    }
}
