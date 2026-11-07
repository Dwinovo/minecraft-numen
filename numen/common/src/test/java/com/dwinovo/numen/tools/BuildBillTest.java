package com.dwinovo.numen.tools;

import com.dwinovo.numen.task.build.BuildTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code numen.build.diff} 报料:剩下要放的格要花的全部物品(件数与逐格实扣同一个函数,双层砖一格两件),生存模式下她还缺多少——
 * 背包只数 36 格主背包,副手上那叠不算;免耗材时没有缺口这一项。
 */
@Tag("mc")
class BuildBillTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 九格圆石加一格双层石砖台阶。 */
    private static Map<Item, Integer> nineCobbleAndADoubleSlab() {
        List<BuildTaskRecord.Target> cells = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE, new BlockPos(x, 64, z), "cobblestone"));
            }
        }
        cells.add(new BuildTaskRecord.Target(Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
                Items.STONE_BRICK_SLAB, new BlockPos(0, 65, 0), "stone_brick_slab"));
        return BuildBill.cost(cells);
    }

    @Test
    void tenCellsCostNineCobblestoneAndTwoSlabs() {
        assertEquals(Map.of("cobblestone", 9, "stone_brick_slab", 2), BuildBill.items(nineCobbleAndADoubleSlab()));
    }

    /** 主背包里五块圆石、一块台阶,副手上一整叠圆石:缺四块圆石、一块台阶——副手那叠施工花不到,报价也不数它。 */
    @Test
    void theShortfallCountsOnlyTheMainSlotsNotTheOffhandStack() {
        Inventory carried = new Inventory(null);
        carried.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        carried.setItem(1, new ItemStack(Items.STONE_BRICK_SLAB, 1));
        carried.offhand.set(0, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(Optional.of(Map.of("cobblestone", 4, "stone_brick_slab", 1)),
                BuildOps.shortOf(false, carried, nineCobbleAndADoubleSlab()));
    }

    @Test
    void buildingThatCostsNothingHasNoShortfall() {
        assertEquals(Optional.empty(), BuildOps.shortOf(true, new Inventory(null), nineCobbleAndADoubleSlab()));
    }
}
