package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Arena;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

/** 场景断言共用的数数:场地里立着几个某种方块、某种物品交到了主人手里几个。 */
final class Tally {

    /** 掉在主人碰撞箱外扩这么远以内,算交到了主人手里。 */
    static final double HANDOVER = 5;

    private Tally() {}

    /** 场地(地板以上)里立着几个 {@code block}。 */
    static int blocks(Scene scene, Arena arena, Block block) {
        int n = 0;
        for (int x = 0; x < arena.size(); x++) {
            for (int z = 0; z < arena.size(); z++) {
                for (int y = 1; y <= arena.height(); y++) {
                    n += scene.level().getBlockState(scene.pos(x, y, z)).is(block) ? 1 : 0;
                }
            }
        }
        return n;
    }

    /** 她包里、主人包里、主人碰撞箱外扩 {@value #HANDOVER} 格以内地上的 {@code item} 总数。 */
    static int handedOver(Scene scene, Item item) {
        int n = scene.her().getInventory().countItem(item) + scene.owner().getInventory().countItem(item);
        AABB near = scene.owner().getBoundingBox().inflate(HANDOVER);
        for (ItemEntity drop : scene.level().getEntitiesOfClass(ItemEntity.class, near,
                e -> e.getItem().is(item))) {
            n += drop.getItem().getCount();
        }
        return n;
    }
}
