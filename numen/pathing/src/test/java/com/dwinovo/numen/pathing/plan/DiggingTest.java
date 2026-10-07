package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 挖掘:物理上能不能挖、挖多久(原版公式)、用哪件工具。 */
class DiggingTest {

    private static final BlockPos AT = new BlockPos(0, 64, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static BlockState log() {
        return Blocks.OAK_LOG.defaultBlockState();
    }

    // ==================== 挖多久 ====================

    @Test
    void anOakLogTakesThreeSecondsByHandLikeVanilla() {
        // 硬度 2,空手速度 1,原木不要求对的工具:每刻进度 1 / 2 / 30,60 刻碎
        BodySnapshot body = Fixtures.body();
        assertEquals(60, DigTime.ticks(body, ItemStack.EMPTY, log(), false, true));
    }

    @Test
    void diggingUnderWaterOrOffTheGroundIsFiveTimesSlowerAndBothStack() {
        BodySnapshot body = Fixtures.body();
        assertEquals(300, DigTime.ticks(body, ItemStack.EMPTY, log(), true, true), "眼睛泡在水里乘 0.2");
        assertEquals(300, DigTime.ticks(body, ItemStack.EMPTY, log(), false, false), "脚不着地除以 5");
        assertEquals(1500, DigTime.ticks(body, ItemStack.EMPTY, log(), true, false));
    }

    @Test
    void theRightToolCountsAndTheWrongOneIsDividedByAHundred() {
        BodySnapshot body = Fixtures.body();
        BlockState stone = Blocks.STONE.defaultBlockState();
        // 空手挖石头:硬度 1.5,不是对的工具,1 / 1.5 / 100
        assertEquals(150, DigTime.ticks(body, ItemStack.EMPTY, stone, false, true));
        // 木镐:速度 2,对的工具,2 / 1.5 / 30,22.5 刻向上取整
        assertEquals(23, DigTime.ticks(body, new ItemStack(Items.WOODEN_PICKAXE), stone, false, true));
    }

    @Test
    void creativeModeBreaksEverythingAtOnce() {
        BodySnapshot creative = Fixtures.body(Vanilla.CREATIVE, GameType.CREATIVE, 20, List.of());
        assertEquals(1, DigTime.ticks(creative, ItemStack.EMPTY, Blocks.OBSIDIAN.defaultBlockState(), false, true));
    }

    /**
     * 挖碎一格之后缓手(原版客户端的 {@code destroyDelay}):累着进度挖碎的、创造模式挖掉的缓 5 刻,生存模式一下就碎的不缓。
     * 挖一格手上一共要花的刻数是挖到碎的刻数加上它。
     */
    @Test
    void theHandRestsFiveTicksAfterABlockThatTookProgressToBreak() {
        BodySnapshot body = Fixtures.body();
        ToolChoice tools = new ToolChoice(body);
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertEquals(5, DigTime.cooldown(false, false));
        assertEquals(5, DigTime.cooldown(true, true), "创造模式照样缓");
        assertEquals(0, DigTime.cooldown(false, true), "一下就碎的不缓");
        assertEquals(150 + 5, tools.handTicks(stone, false, true), 1e-9, "空手挖石头 150 刻,再缓 5 刻");
        assertEquals(1, tools.handTicks(Blocks.SHORT_GRASS.defaultBlockState(), false, true), 1e-9, "草一下就碎,不缓");
        ToolChoice creative = new ToolChoice(Fixtures.body(Vanilla.CREATIVE, GameType.CREATIVE, 20, List.of()));
        assertEquals(1 + 5, creative.handTicks(Blocks.OBSIDIAN.defaultBlockState(), false, true), 1e-9);
    }

    @Test
    void theBestToolDeepInTheInventoryIsPickedAndPriced() {
        List<ItemStack> inventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inventory.add(ItemStack.EMPTY);
        }
        inventory.set(0, new ItemStack(Items.WOODEN_PICKAXE));
        inventory.set(31, new ItemStack(Items.DIAMOND_PICKAXE));
        BodySnapshot body = Fixtures.body(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, inventory);
        ToolChoice tools = new ToolChoice(body);
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertEquals(31, tools.best(stone).slot(), "背包深处的钻石镐");
        assertEquals(DigTime.ticks(body, new ItemStack(Items.DIAMOND_PICKAXE), stone, false, true),
                tools.ticks(stone, false, true));
        assertEquals(ToolChoice.Pick.BARE_HAND, tools.best(Blocks.DIRT.defaultBlockState()).slot(),
                "挖泥土镐子不比空手快,不为它磨损镐子");
    }

    /**
     * 身体快照抄下的是背包那一刻的样子:之后宿主改了原来那份清单(把镐子拿走、别的槽位塞进更好的),或者原来那件东西本身
     * 被用掉,挑工具看的还是快照里那份。
     */
    @Test
    void theSnapshotKeepsTheInventoryAsItWasWhenTaken() {
        List<ItemStack> inventory = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inventory.add(ItemStack.EMPTY);
        }
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        inventory.set(5, pickaxe);
        BodySnapshot body = Fixtures.body(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, inventory);
        inventory.set(5, ItemStack.EMPTY);
        inventory.set(2, new ItemStack(Items.NETHERITE_PICKAXE));
        pickaxe.shrink(1);
        ToolChoice.Pick pick = new ToolChoice(body).best(Blocks.STONE.defaultBlockState());
        assertEquals(5, pick.slot());
        assertEquals(Items.DIAMOND_PICKAXE, pick.tool().getItem());
    }

    // ==================== 能不能挖 ====================

    private static Reason check(TestWorld world, BlockPos pos) {
        return DigRules.check(world, Fixtures.body(), pos, world.getBlockState(pos), false);
    }

    private static TestWorld stoneAt() {
        return new TestWorld().set(AT, Blocks.STONE.defaultBlockState());
    }

    @Test
    void anOrdinaryBlockMayBeDug() {
        assertNull(check(stoneAt(), AT));
    }

    @Test
    void liquidAboveOrASourceBesideWouldFloodTheHole() {
        assertEquals(Reason.WOULD_FLOOD, check(stoneAt().set(AT.above(), Blocks.WATER.defaultBlockState()), AT));
        assertEquals(Reason.WOULD_FLOOD, check(stoneAt().set(AT.east(), Blocks.LAVA.defaultBlockState()), AT));
        BlockState falling = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        TestWorld pouring = stoneAt().set(AT.east(), falling).set(AT.east().below(), Blocks.WATER.defaultBlockState());
        assertNull(check(pouring, AT), "旁边流动的水往下落进水里,不横着流过来");
        TestWorld strict = stoneAt().set(AT.east(), falling).set(AT.east().below(), Blocks.WATER.defaultBlockState());
        assertEquals(Reason.WOULD_FLOOD, DigRules.check(strict, Fixtures.body(), AT, strict.getBlockState(AT), true),
                "从严时邻格有任何液体都不挖");
    }

    @Test
    void sandAboveOrFloatingSandBesideWouldCollapse() {
        assertEquals(Reason.WOULD_COLLAPSE, check(stoneAt().set(AT.above(), Blocks.SAND.defaultBlockState()), AT));
        assertEquals(Reason.WOULD_COLLAPSE, check(stoneAt().set(AT.east(), Blocks.GRAVEL.defaultBlockState()), AT),
                "旁边悬着的沙砾被方块更新惊动就掉");
        TestWorld resting = stoneAt().set(AT.east(), Blocks.GRAVEL.defaultBlockState())
                .set(AT.east().below(), Blocks.STONE.defaultBlockState());
        assertNull(check(resting, AT), "旁边的沙砾下面有东西托着,不掉");
    }

    @Test
    void iceInfestedBlocksBedrockAndTheBorderAreLeftAlone() {
        TestWorld ice = new TestWorld().set(AT, Blocks.ICE.defaultBlockState()).set(AT.below(), Blocks.STONE.defaultBlockState());
        assertEquals(Reason.MELTS, check(ice, AT));
        assertEquals(Reason.INFESTED, check(new TestWorld().set(AT, Blocks.INFESTED_STONE.defaultBlockState()), AT));
        assertEquals(Reason.UNBREAKABLE, check(new TestWorld().set(AT, Blocks.BEDROCK.defaultBlockState()), AT));
        TestWorld small = stoneAt();
        small.border().setSize(10);
        BlockPos outside = new BlockPos(6, 64, 0);
        small.set(outside, Blocks.STONE.defaultBlockState());
        assertEquals(Reason.OUT_OF_BOUNDS, check(small, outside));
        BodySnapshot adventure = Fixtures.body(Vanilla.SURVIVAL, GameType.ADVENTURE, 20, List.of());
        assertEquals(Reason.EDIT_RESTRICTED, DigRules.check(stoneAt(), adventure, AT, Blocks.STONE.defaultBlockState(), false));
    }
}
