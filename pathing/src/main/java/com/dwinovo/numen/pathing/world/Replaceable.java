package com.dwinovo.numen.pathing.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SculkVeinBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 放置时方块真正落在哪一格,照原版 {@code BlockPlaceContext}:点中的那一格本身可以被顶替(高草、单层雪、藤蔓……),方块就落
 * 进点中的那一格;否则落进点中那一面前面的一格,那一格也得可以被顶替,不然放不下。规划要问许可、记账的是这一格,
 * 执行时以真实落点为准。
 *
 * <p>"可以被顶替"是原版 {@code BlockState.canBeReplaced(BlockPlaceContext)} 对"放一个与它不同种的方块"的回答。那个方法要一个
 * 带 {@code Level} 的放置上下文,搜索用的只读视图给不出,所以这里按同一套规则直接回答:默认看方块的 replaceable 属性
 * (空气、流体、草、花、火……);原版 1.21.1 里对"放别的方块"答得和属性不一样的,是雪层(只有一层时)、藤蔓(五个面
 * 没长满时)、发光地衣与幽匿脉络(总是)、瓶子草作物(从不)。放的与这一格同种时原版是叠放(加一层雪、合成双层半砖),
 * 这一格没有空出来给新方块,答不能。
 */
public final class Replaceable {

    private Replaceable() {}

    /** 拿着 {@code placing} 这种方块往 {@code state} 所在的格里放,原来的方块能不能被直接顶替。 */
    public static boolean replaceableBy(BlockState state, Block placing) {
        Block block = state.getBlock();
        if (block == placing) {
            return false;
        }
        if (block instanceof SnowLayerBlock) {
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block instanceof VineBlock) {
            return VineBlock.PROPERTY_BY_DIRECTION.values().stream().filter(state::getValue).count()
                    < VineBlock.PROPERTY_BY_DIRECTION.size();
        }
        if (block instanceof GlowLichenBlock || block instanceof SculkVeinBlock) {
            return true;
        }
        if (block instanceof PitcherCropBlock) {
            return false;
        }
        return state.canBeReplaced();
    }

    /**
     * 拿着 {@code placing} 点 {@code clicked} 这一格的 {@code face} 面,方块真正落在哪一格;放不下返回 null。
     */
    public static BlockPos landing(BlockGetter level, BlockPos clicked, Direction face, Block placing) {
        if (replaceableBy(level.getBlockState(clicked), placing)) {
            return clicked;
        }
        BlockPos front = clicked.relative(face);
        return replaceableBy(level.getBlockState(front), placing) ? front : null;
    }
}
