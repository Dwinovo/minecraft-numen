package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 墙里只露一面的箱子:一堵三格厚的石墙,箱子嵌在墙根,只有朝她的那一面露在外面(头顶一格玻璃——实心方块压着箱子就打不开),
 * 里面 5 颗钻石。主人只说把箱子里的钻石拿给他。要成事得找到箱子、站到那一面够得着的地方、打开、把钻石拿出来。
 * 成功 = 钻石在她包里,或者交到了主人手里(主人包里,或者丢在主人脚边——模拟主人不走动、不捡东西)。
 */
public final class WalledChest implements Scenario {

    private static final BlockPos CHEST = new BlockPos(10, 1, 12);
    private static final int DIAMONDS = 5;

    @Override
    public String id() {
        return "chest_in_wall";
    }

    @Override
    public BlockPos start() {
        return new BlockPos(10, 1, 4);
    }

    @Override
    public BlockPos ownerAt() {
        return new BlockPos(3, 1, 3);
    }

    @Override
    public void setup(Scene scene) {
        for (int x = CHEST.getX() - 3; x <= CHEST.getX() + 3; x++) {
            for (int z = CHEST.getZ(); z <= CHEST.getZ() + 2; z++) {
                for (int y = 1; y <= 3; y++) {
                    scene.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        scene.set(CHEST.getX(), CHEST.getY(), CHEST.getZ(),
                Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        scene.set(CHEST.getX(), CHEST.getY() + 1, CHEST.getZ(), Blocks.GLASS.defaultBlockState());
        ((ChestBlockEntity) scene.level().getBlockEntity(scene.pos(CHEST)))
                .setItem(0, new ItemStack(Items.DIAMOND, DIAMONDS));
    }

    @Override
    public String opening() {
        return "把箱子里的钻石拿给我。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("钻石到手", s -> s.assertTrue(delivered(s) >= DIAMONDS,
                        "她包里 " + s.her().getInventory().countItem(Items.DIAMOND) + " 颗,主人那里 "
                                + (delivered(s) - s.her().getInventory().countItem(Items.DIAMOND)) + " 颗")),
                Check.subgoal("箱子里的钻石拿出来了", s -> s.assertTrue(inChest(s) == 0,
                        "箱子里还有 " + inChest(s) + " 颗")));
    }

    /** 她包里、主人包里、主人脚边地上的钻石。 */
    private static int delivered(Scene scene) {
        int n = scene.her().getInventory().countItem(Items.DIAMOND)
                + scene.owner().getInventory().countItem(Items.DIAMOND);
        AABB feet = scene.owner().getBoundingBox().inflate(2.5);
        for (ItemEntity drop : scene.level().getEntitiesOfClass(ItemEntity.class, feet,
                e -> e.getItem().is(Items.DIAMOND))) {
            n += drop.getItem().getCount();
        }
        return n;
    }

    private static int inChest(Scene scene) {
        return scene.level().getBlockEntity(scene.pos(CHEST)) instanceof ChestBlockEntity chest
                ? chest.countItem(Items.DIAMOND) : 0;
    }

    @Override
    public String solution(Scene scene) {
        BlockPos chest = scene.pos(CHEST);
        String cell = "{x = " + chest.getX() + ", y = " + chest.getY() + ", z = " + chest.getZ() + "}";
        return """
                numen.move.goto_(%1$s, {arrive = "use"})
                numen.use.block(%1$s)
                numen.use.shift(0)
                numen.use.close()""".formatted(cell);
    }
}
