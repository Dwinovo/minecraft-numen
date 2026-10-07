package com.dwinovo.numen.pathing.plan;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 全部走法,每种一个实现。搜索逐个展开它们;执行复核按走法取回同一个实现再判一次前提。 */
public final class Moves {

    public static final List<Move> ALL = List.of(
            new Walk(), new Diagonal(), new Ascend(), new Drop(MoveKind.DESCEND), new Drop(MoveKind.FALL),
            new Parkour(), new Pillar(), new Downward(), new Climb(), new Swim());

    private static final Map<MoveKind, Move> BY_KIND = new EnumMap<>(MoveKind.class);

    static {
        for (Move move : ALL) {
            if (BY_KIND.put(move.kind(), move) != null) {
                throw new IllegalStateException("走法 " + move.kind() + " 有两个实现");
            }
        }
        if (BY_KIND.size() != MoveKind.values().length) {
            throw new IllegalStateException("有走法没有实现");
        }
    }

    private Moves() {}

    public static Move of(MoveKind kind) {
        return BY_KIND.get(kind);
    }
}
