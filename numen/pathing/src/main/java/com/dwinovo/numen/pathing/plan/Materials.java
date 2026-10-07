package com.dwinovo.numen.pathing.plan;

import java.util.Optional;

import net.minecraft.world.level.block.Block;

/**
 * 端口:垫路料。规划在建成本模型时问一次,整次搜索按这一块定价;有没有料就是它有没有答案。
 *
 * <p>"有没有料"和"许不许改地形"是两个独立的事实:后者来自路线规格的 {@code place},前者只来自这里,规划从不把两者
 * 折成一个布尔。
 */
@FunctionalInterface
public interface Materials {

    /** 身上什么料都没有。 */
    Materials NONE = Optional::empty;

    /** 下一块垫路会放下哪种方块;身上没有可垫的料时为空。 */
    Optional<Block> next();
}
