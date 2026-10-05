package com.dwinovo.numen.pathing.spec;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.level.block.Block;

/**
 * 按方块种类的禁令,与 {@link PositionCosts} 互补:位置表回答"这一格",这张表回答"这一种"。三栏各自独立——不挖这些
 * (箱子、原木)、不往装着这些的格里放(水源、高草)、不站在这些上面。标签由调用方展开成成员后交进来,搜索热路径只做
 * 集合查询。
 *
 * <p>出厂的"不踩耕地、海龟蛋、压力板、绊线"不写在这里:它们是 {@link RouteSpec#excluded()} 里排除的语义种类,
 * 哪些方块属于这几种由第 0 层的语义表一处回答。这张表只装调用方点名的种类。
 */
public record BlockBans(Set<Block> breaking, Set<Block> placingInto, Set<Block> standingOn) {

    public static final BlockBans EMPTY = new BlockBans(Set.of(), Set.of(), Set.of());

    public BlockBans {
        breaking = Set.copyOf(breaking);
        placingInto = Set.copyOf(placingInto);
        standingOn = Set.copyOf(standingOn);
    }

    public boolean isEmpty() {
        return breaking.isEmpty() && placingInto.isEmpty() && standingOn.isEmpty();
    }

    /** 两份禁令合并:每栏取并集。 */
    public BlockBans plus(BlockBans other) {
        return new BlockBans(union(breaking, other.breaking), union(placingInto, other.placingInto),
                union(standingOn, other.standingOn));
    }

    private static Set<Block> union(Set<Block> a, Set<Block> b) {
        Set<Block> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
