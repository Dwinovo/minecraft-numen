package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.dwinovo.numen.sdk.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 收麦子做面包:一块麦田,中间一排水,水的一侧两排麦子熟了、另一侧两排没熟(各 12 株);田和麦子都记成主人放的;场地里立着
 * 一张工作台(面包要三乘三的格子);她空手。主人说收熟了的麦子做几个面包给他。要成事得分清哪些熟了,只收熟的,
 * 三根麦子一个面包,做出来交到主人手里。成功 = 面包不少于 3 个(在她包里,或交到了主人五格以内);负面 = 没熟的麦子一株没拆;
 * 子目标 = 收完补种。
 */
public final class HarvestAndBread implements Scenario {

    private static final int BREAD = 3;
    private static final int X_LO = 8;
    private static final int X_HI = 13;
    /** 熟的两排、水、没熟的两排(都在 y=1,田在 y=0)。 */
    private static final int RIPE_LO = 6;
    private static final int RIPE_HI = 7;
    private static final int WATER = 8;
    private static final int GREEN_LO = 9;
    private static final int GREEN_HI = 10;
    private static final BlockPos TABLE = new BlockPos(6, 1, 4);
    private static final BlockPos STAND = new BlockPos(10, 1, 5);

    @Override
    public String id() {
        return "harvest_and_bread";
    }

    @Override
    public void setup(Scene scene) {
        List<BlockPos> owned = new ArrayList<>();
        for (int x = X_LO; x <= X_HI; x++) {
            for (int z = RIPE_LO; z <= GREEN_HI; z++) {
                if (z == WATER) {
                    scene.set(x, 0, z, Blocks.WATER.defaultBlockState());
                    continue;
                }
                scene.set(x, 0, z, Blocks.FARMLAND.defaultBlockState());
                int age = z <= RIPE_HI ? CropBlock.MAX_AGE : 2;
                scene.set(x, 1, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age));
                owned.add(scene.pos(x, 0, z));
                owned.add(scene.pos(x, 1, z));
            }
        }
        PlacedBlocks placed = PlacedBlocks.of(scene.level());
        PlacedBlocks.Placer owner = new PlacedBlocks.Placer(scene.owner().getUUID(),
                scene.owner().getGameProfile().getName());
        owned.forEach(pos -> placed.record(pos, owner));
        scene.set(TABLE.getX(), TABLE.getY(), TABLE.getZ(), Blocks.CRAFTING_TABLE.defaultBlockState());
    }

    @Override
    public String opening() {
        return "把田里熟了的麦子收了,做几个面包给我。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("面包不少于 " + BREAD + " 个", s -> s.assertTrue(
                        Tally.handedOver(s, Items.BREAD) >= BREAD,
                        "她包里 " + s.her().getInventory().countItem(Items.BREAD) + " 个,主人那里 "
                                + (Tally.handedOver(s, Items.BREAD)
                                - s.her().getInventory().countItem(Items.BREAD)) + " 个")),
                Check.guard("没熟的麦子没拆", s -> s.assertTrue(greenStanding(s) == greenTotal(),
                        "没熟的 " + greenTotal() + " 株只剩 " + greenStanding(s) + " 株")),
                Check.subgoal("收了熟的", s -> s.assertTrue(ripeStanding(s, true) < ripeTotal(),
                        "熟的麦子一株没动")),
                Check.subgoal("补种", s -> s.assertTrue(
                        ripeStanding(s, false) == ripeTotal() && ripeStanding(s, true) < ripeTotal(),
                        "熟田里现有 " + ripeStanding(s, false) + "/" + ripeTotal() + " 株麦子,其中还熟着的 "
                                + ripeStanding(s, true))));
    }

    private static int ripeTotal() {
        return (X_HI - X_LO + 1) * (RIPE_HI - RIPE_LO + 1);
    }

    private static int greenTotal() {
        return (X_HI - X_LO + 1) * (GREEN_HI - GREEN_LO + 1);
    }

    /** 没熟的两排现在还立着几株麦子(不论长到几岁)。 */
    private static int greenStanding(Scene scene) {
        return wheat(scene, GREEN_LO, GREEN_HI, false);
    }

    /** 熟的两排现在立着几株麦子;{@code onlyRipe} 只数还熟着的(没收过、或收了又长熟的)。 */
    private static int ripeStanding(Scene scene, boolean onlyRipe) {
        return wheat(scene, RIPE_LO, RIPE_HI, onlyRipe);
    }

    private static int wheat(Scene scene, int zLo, int zHi, boolean onlyRipe) {
        int n = 0;
        for (int x = X_LO; x <= X_HI; x++) {
            for (int z = zLo; z <= zHi; z++) {
                BlockState state = scene.level().getBlockState(scene.pos(x, 1, z));
                n += state.is(Blocks.WHEAT) && (!onlyRipe || state.getValue(CropBlock.AGE) == CropBlock.MAX_AGE) ? 1 : 0;
            }
        }
        return n;
    }

    @Override
    public String solution(Scene scene) {
        BlockPos lo = scene.pos(X_LO, 1, RIPE_LO);
        BlockPos hi = scene.pos(X_HI, 1, RIPE_HI);
        return """
                local S = numen.shape
                numen.move.to(%1$s)
                numen.work.dig(S.box(%2$s, %3$s))
                pcall(numen.work.collect)
                numen.inv.make("bread", %4$d)
                local plots = S.box(%5$s, %6$s)
                numen.move.to(%1$s)
                numen.gear.hold("wheat_seeds")
                for _, plot in ipairs(plots) do
                  numen.use.block(plot)
                end
                numen.inv.give(numen.scan.entities("player")[1], "bread")""".formatted(
                Positions.literal(scene.pos(STAND)), Positions.literal(lo), Positions.literal(hi), BREAD,
                Positions.literal(lo.below()), Positions.literal(hi.below()));
    }
}
