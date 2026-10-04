package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Arena;
import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 挖深处的钻石:整块场地是十四层实心石头,她和主人站在顶上;一块钻石矿埋在她正下方 12 格,包里一把铁镐。钻石在她手够不着
 * 的地方,得先往下开路到够得着的地方(挖路、垫脚),再挖、再捡。成功 = 钻石挖上来了:在她包里,或者交到了主人手里(主人包里,
 * 或者丢在主人身边的地上)。
 */
public final class DeepDiamond implements Scenario {

    /** 石层顶在 y=14,站在上面是 y=15;场地要高出这一截。 */
    private static final int TOP = 14;
    private static final Arena ARENA = new Arena(20, TOP + 4);
    /** 她脚下 12 格。 */
    private static final BlockPos ORE = new BlockPos(10, TOP + 1 - 12, 10);

    @Override
    public String id() {
        return "dig_deep_diamond";
    }

    @Override
    public Arena arena() {
        return ARENA;
    }

    @Override
    public BlockPos start() {
        return new BlockPos(ORE.getX(), TOP + 1, ORE.getZ());
    }

    @Override
    public BlockPos ownerAt() {
        return new BlockPos(2, TOP + 1, 2);
    }

    @Override
    public void setup(Scene scene) {
        for (int x = 0; x < ARENA.size(); x++) {
            for (int z = 0; z < ARENA.size(); z++) {
                for (int y = 1; y <= TOP; y++) {
                    scene.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        scene.set(ORE.getX(), ORE.getY(), ORE.getZ(), Blocks.DIAMOND_ORE.defaultBlockState());
        scene.give(new ItemStack(Items.IRON_PICKAXE));
    }

    @Override
    public String opening() {
        return "我们脚底下深处埋着钻石,去挖上来。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("钻石挖上来了", s -> s.assertTrue(Tally.handedOver(s, Items.DIAMOND) >= 1,
                        "她包里、主人包里、主人身边的地上都没有钻石")),
                Check.subgoal("往下开了一半的路", s -> s.assertTrue(dugAbove(s) >= (TOP - ORE.getY()) / 2,
                        "矿上方只挖开了 " + dugAbove(s) + " 格")),
                Check.subgoal("矿挖掉了", s -> s.assertTrue(
                        !s.level().getBlockState(s.pos(ORE)).is(Blocks.DIAMOND_ORE), "钻石矿还在原处")));
    }

    /** 矿上方五乘五的柱子里挖开了几层:哪一层有一格不是石头就算这一层开了。 */
    private static int dugAbove(Scene scene) {
        int layers = 0;
        for (int y = ORE.getY() + 1; y <= TOP; y++) {
            boolean open = false;
            for (int x = ORE.getX() - 2; x <= ORE.getX() + 2 && !open; x++) {
                for (int z = ORE.getZ() - 2; z <= ORE.getZ() + 2 && !open; z++) {
                    open = !scene.level().getBlockState(scene.pos(x, y, z)).is(Blocks.STONE);
                }
            }
            layers += open ? 1 : 0;
        }
        return layers;
    }

    @Override
    public String solution(Scene scene) {
        BlockPos ore = scene.pos(ORE);
        String cell = "{x = " + ore.getX() + ", y = " + ore.getY() + ", z = " + ore.getZ() + "}";
        return """
                numen.move.to(%1$s, {arrive = "dig", costs = {dig = true, place = true, consent = false}})
                numen.work.dig(%1$s)
                numen.work.collect()""".formatted(cell);
    }
}
