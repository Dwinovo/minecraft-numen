package com.dwinovo.numen.api.witness;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * 一次动作对着谁:哪一格方块、哪只实体。对着空气用手里的东西、在开着的界面里做事,两样都没有。
 *
 * @param block  瞄着的那一格;没有为 null
 * @param entity 对着的那只实体;没有为 null
 */
public record Focus(BlockPos block, Entity entity) {

    /** 不对着世界里的哪一样:只关她自己。 */
    public static final Focus SELF = new Focus(null, null);

    public Focus {
        block = block == null ? null : block.immutable();
    }

    /** 对着一格方块;{@code block} 为 null 就是 {@link #SELF}。 */
    public static Focus on(BlockPos block) {
        return block == null ? SELF : new Focus(block, null);
    }

    /** 对着一只实体。 */
    public static Focus on(Entity entity) {
        return entity == null ? SELF : new Focus(null, entity);
    }
}
