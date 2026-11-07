package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.sdk.BlockAt;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WorldEdit 自己的那几个算法(花草、南瓜田、松树)的判据;原版生成器本身在游戏里测。世界是 {@link FakeBlocks},地面最高一层在 y = -1。 */
class GeneratorsTest {

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    private static List<BlockPos> columns(int from, int to) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = from; x <= to; x++) {
            for (int z = from; z <= to; z++) {
                out.add(new BlockPos(x, 0, z));
            }
        }
        return out;
    }

    private static FakeBlocks grass(int from, int to) {
        return FakeBlocks.flat(-1).fill(from, -1, from, to, -1, to, "grass_block");
    }

    @Test
    void floraGrowsOneThingAboveEveryGrassColumnAtFullDensityAndNothingBelowZero() {
        List<BlockAt> flora = Generators.flora(grass(0, 9), columns(0, 9), -5, 5, 1.0);
        assertEquals(100, flora.size(), "grass takes a plant every time");
        assertTrue(flora.stream().allMatch(b -> b.pos().getY() == 0
                && Set.of("minecraft:short_grass", "minecraft:poppy", "minecraft:dandelion").contains(b.name())),
                flora.toString());
        assertTrue(Generators.flora(grass(0, 9), columns(0, 9), -5, 5, 0.0).isEmpty());
        // stone ground grows nothing
        assertTrue(Generators.flora(FakeBlocks.flat(-1), columns(0, 9), -5, 5, 1.0).isEmpty());
    }

    @Test
    void floraOnSandIsDeadBushOrCactusAndMostColumnsStayBare() {
        FakeBlocks sand = FakeBlocks.flat(-1).fill(0, -1, 0, 29, -1, 29, "sand");
        List<BlockAt> flora = Generators.flora(sand, columns(0, 29), -5, 5, 1.0);
        assertTrue(flora.stream().allMatch(b -> b.name().equals("minecraft:dead_bush") || b.name().equals("minecraft:cactus")));
        // 50 of 350 by weight: about 14% of 900 columns
        assertTrue(flora.size() > 50 && flora.size() < 250, "" + flora.size());
    }

    @Test
    void aColumnWithSomethingOnTopOfTheSearchedHeightIsUndergroundAndSkipped() {
        FakeBlocks covered = grass(0, 9).fill(0, 5, 0, 9, 5, 9, "stone");
        assertTrue(Generators.flora(covered, columns(0, 9), -5, 4, 1.0).isEmpty(), "stone at y 5 is above the top of the search");
    }

    @Test
    void pumpkinPatchesAreLeavesLogsAndPumpkinsOnGrass() {
        List<BlockAt> patches = Generators.pumpkins(grass(-25, 25), new BlockPos(0, 0, 0), 20);
        assertTrue(patches.stream().anyMatch(b -> b.name().equals("minecraft:pumpkin")), "a 41 x 41 field has patches");
        assertTrue(patches.stream().allMatch(b -> Set.of("minecraft:oak_leaves[persistent=true]", "minecraft:oak_log",
                "minecraft:pumpkin").contains(b.name())), patches.toString());
        assertTrue(Generators.pumpkins(FakeBlocks.flat(-1), new BlockPos(0, 0, 0), 20).isEmpty(), "only grass grows them");
    }

    @Test
    void aPineHasATrunkOfLogsFromTheGroundAndOnlyLogsAndLeaves() {
        Generators.Grown grown = Generators.forest(FakeBlocks.flat(-1), List.of(new BlockPos(0, 0, 0)), -5, 5,
                Generators.PINE_TREE, 1.0);
        assertTrue(grown.lost().isEmpty());
        assertTrue(grown.blocks().stream().anyMatch(b -> b.name().equals("minecraft:oak_log") && b.pos().equals(new BlockPos(0, 0, 0))),
                "the trunk starts on the cell above the ground");
        assertTrue(grown.blocks().stream().allMatch(b -> b.name().equals("minecraft:oak_log")
                || b.name().startsWith("minecraft:oak_leaves")), grown.blocks().toString());
        assertTrue(grown.blocks().stream().filter(b -> b.name().equals("minecraft:oak_log")).count() >= 3 + 8);
    }
}
