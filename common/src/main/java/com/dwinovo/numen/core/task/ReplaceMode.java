package com.dwinovo.numen.core.task;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 目标格上已经有东西时,让不让路。
 *
 * <p>四档,从最保守到最霸道。此前只有一个布尔量"替不替换",而那个布尔量把两件不同
 * 的事捆在了一起:<b>要不要顶掉挡路的</b>,和<b>要不要把该空的地方清空</b>。往一栋
 * 老房子上加东西时,人要的往往是"只往空地上补,已有的一律别碰"——那是这里的
 * {@link #DONT_REPLACE},用布尔量表达不出来。
 *
 * <p>"软"的判据用 {@link BlockState#canBeReplaced()}:草、花、水草、雪层、火——
 * 原版自己判断"能不能直接盖上去"用的就是它,所以不必另立一套近似判据。
 */
public enum ReplaceMode {

    /** 只往空地和软方块上放:既有建筑一格都不碰,也不清空。 */
    DONT_REPLACE,

    /** 同上,外加"要放的是完整实心块时也可以顶掉"——骨架能压过去,细节不行。 */
    REPLACE_SOLID,

    /** 挡路的一律顶掉,但不清空:图纸里的空气格当作"不管这一格"。 */
    REPLACE_ANY,

    /** 连该空的地方也清掉——图纸里的空气格是"把这里挖空"的指令。 */
    REPLACE_EMPTY;

    /**
     * 这一格现在该不该动手。
     *
     * @param current 世界里现在是什么
     * @param desired 图纸要它变成什么(空气 = 清空)
     */
    public boolean allows(BlockState current, BlockState desired) {
        boolean clearing = desired == null || desired.isAir();
        if (clearing) {
            return this == REPLACE_EMPTY;   // 只有最高那档做清场
        }
        if (this == REPLACE_ANY || this == REPLACE_EMPTY) {
            return true;
        }
        boolean soft = current.isAir() || current.canBeReplaced();
        if (soft) {
            return true;
        }
        // 实心挡着:只有 REPLACE_SOLID 才允许,而且只允许实心压实心——
        // 拿一根火把去顶掉一面墙不是任何人想要的
        return this == REPLACE_SOLID && desired.isCollisionShapeFullBlock(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                net.minecraft.core.BlockPos.ZERO);
    }
}
