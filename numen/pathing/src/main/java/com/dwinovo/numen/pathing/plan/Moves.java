package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 全部走法,每种一个实现。搜索逐个展开这具身体这一趟能用的走法({@link #forBody},由身体身上已有的能力定);执行复核按走法取回同一个实现再判一次前提({@link #of})。
 */
public final class Moves {

    /** 走、游、挖、搭:每具身体都有。 */
    private static final List<Move> WALKING = List.of(
            new Walk(), new Diagonal(), new Ascend(), new Drop(MoveKind.DESCEND), new Drop(MoveKind.FALL),
            new Parkour(), new Pillar(), new Downward(), new Climb(), new Swim());

    /** 许飞的身体:走的那些,再加起飞、飞、降落——排在后面,走路的走法在表里的位置与不许飞时一样。 */
    private static final List<Move> FLYING;

    static {
        List<Move> all = new ArrayList<>(WALKING);
        all.add(new Takeoff());
        all.add(new Fly());
        all.add(new Land());
        FLYING = List.copyOf(all);
    }

    private static final Map<MoveKind, Move> BY_KIND = new EnumMap<>(MoveKind.class);

    static {
        for (Move move : FLYING) {
            if (BY_KIND.put(move.kind(), move) != null) {
                throw new IllegalStateException("走法 " + move.kind() + " 有两个实现");
            }
        }
        if (BY_KIND.size() != MoveKind.values().length) {
            throw new IllegalStateException("有走法没有实现");
        }
    }

    private Moves() {}

    /** 这具身体这一趟搜索展开的走法,按这个顺序:搜索节点里记的走法下标就是它在这张表里的位置。 */
    public static List<Move> forBody(BodySnapshot body) {
        return body.mobility().mayFly() ? FLYING : WALKING;
    }

    public static Move of(MoveKind kind) {
        return BY_KIND.get(kind);
    }
}
