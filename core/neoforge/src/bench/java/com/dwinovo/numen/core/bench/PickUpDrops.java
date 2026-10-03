package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Arena;
import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 捡地上的东西:她身边二到八格的一圈地上散着 20 个掉落物(几种杂物,彼此隔开、不会合堆),都不会过期消失——否则什么都不做
 * 等五分钟也"捡干净"了。模拟主人不走动、不捡东西。主人只说把地上的东西都捡起来。成功 = 场地里一个掉落物都不剩。
 */
public final class PickUpDrops implements Scenario {

    private static final int COUNT = 20;
    private static final List<Item> KINDS = List.of(Items.COBBLESTONE, Items.DIRT, Items.STICK, Items.APPLE,
            Items.OAK_SAPLING);
    private static final BlockPos CENTER = new BlockPos(10, 1, 10);

    @Override
    public String id() {
        return "pick_up_drops";
    }

    @Override
    public BlockPos start() {
        return CENTER;
    }

    @Override
    public void setup(Scene scene) {
        for (int i = 0; i < COUNT; i++) {
            // 两圈:内圈 3 格、外圈 7 格,各十个均匀摆开
            double radius = i % 2 == 0 ? 3 : 7;
            double angle = Math.PI * 2 * (i / 2) / (COUNT / 2.0) + (i % 2) * 0.3;
            BlockPos c = scene.pos(CENTER);
            ItemEntity drop = new ItemEntity(scene.level(), c.getX() + 0.5 + radius * Math.cos(angle), c.getY(),
                    c.getZ() + 0.5 + radius * Math.sin(angle), new ItemStack(KINDS.get(i % KINDS.size()), 1 + i % 3),
                    0, 0, 0);
            drop.setUnlimitedLifetime();
            scene.level().addFreshEntity(drop);
        }
    }

    @Override
    public String opening() {
        return "把地上的东西都捡起来。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("地上一个不剩", s -> s.assertTrue(left(s) == 0, "地上还有 " + left(s) + " 个掉落物")),
                Check.subgoal("捡了一半", s -> s.assertTrue(left(s) <= COUNT / 2, "地上还有 " + left(s) + " 个")));
    }

    /** 场地里还躺着的掉落物。 */
    private int left(Scene scene) {
        Arena arena = arena();
        AABB box = new AABB(scene.pos(0, 0, 0).getCenter(), scene.pos(arena.size(), arena.height(), arena.size())
                .getCenter()).inflate(1);
        return scene.level().getEntitiesOfClass(ItemEntity.class, box).size();
    }

    @Override
    public String solution(Scene scene) {
        return "work.collect()";
    }
}
