package com.dwinovo.numen.core.scan;

import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.function.Predicate;

/**
 * "她身边半径 r 内的实体",全仓按半径找实体都从这里问。
 *
 * <p>半径就是离她的距离:先拿外接的方盒向世界要候选(实体分区只认盒子),再按距离滤掉盒角——
 * 方盒的角离她有 r·√3 远,只拿盒子当半径,说好的 16 格实际够到 27 格外。工具对模型说的是半径,
 * 回来的就得是半径以内的。
 */
public final class NearbyEntities {

    private NearbyEntities() {}

    /** {@code self} 半径 {@code radius} 格以内(按两者位置的距离)、类型为 {@code type} 且满足 {@code filter} 的实体,不含她自己。 */
    public static <T extends Entity> List<T> within(Entity self, double radius, Class<T> type,
                                                    Predicate<? super T> filter) {
        double radiusSqr = radius * radius;
        return self.level().getEntitiesOfClass(type, self.getBoundingBox().inflate(radius),
                e -> e != self && self.distanceToSqr(e) <= radiusSqr && filter.test(e));
    }
}
