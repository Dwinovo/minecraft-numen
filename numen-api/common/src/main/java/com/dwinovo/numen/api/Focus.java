package com.dwinovo.numen.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

/**
 * 世界里的一样东西:一格方块,或一只实体。问题拿它说"问的是谁"(她看的是谁,{@link Questions#DESCRIBE})。
 *
 * @param block  那一格;是实体时为 null
 * @param entity 那只实体;是方块时为 null
 */
public record Focus(BlockPos block, Entity entity) {

    public Focus {
        block = block == null ? null : block.immutable();
    }

    /** 一格方块。 */
    public static Focus on(BlockPos block) {
        return new Focus(Objects.requireNonNull(block, "block"), null);
    }

    /** 一只实体。 */
    public static Focus on(Entity entity) {
        return new Focus(null, Objects.requireNonNull(entity, "entity"));
    }
}
