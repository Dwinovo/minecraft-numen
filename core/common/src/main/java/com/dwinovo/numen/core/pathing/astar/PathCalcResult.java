package com.dwinovo.numen.core.pathing.astar;

import java.util.Optional;

/**
 * 一次路径计算的结论:结果类型 + 可空的路径;没到目标的结论还带着搜索为什么停({@link Stop})。
 *
 * <p>"为什么停"跟着结论走,是因为它决定了没路这句话能不能说:搜遍了才叫没有路,预算用完了只是
 * 没搜完。读结论的人(导航的失败回执、规划查询)只看它,不各自去猜。
 */
public final class PathCalcResult {

    public enum Type {
        /** 找到直达目标的完整路径。 */
        SUCCESS_TO_GOAL,
        /** 找到朝目标推进的部分路径段。 */
        SUCCESS_SEGMENT,
        /** 计算被协作取消。 */
        CANCELLATION,
        /** 没找到可用路径。 */
        FAILURE,
        /** 计算过程抛出异常。 */
        EXCEPTION
    }

    /** 搜索停在没到目标的地方,是因为什么。 */
    public enum Stop {
        /** 按这次的规格走得到的格子全搜过了:真的到不了。 */
        EXHAUSTED,
        /** 展开节点的预算用完了还没搜完:到不到得了不知道,离得越远越可能是这样。 */
        BUDGET,
        /** 搜到了没加载的区块边上(撞够了边界,或已加载的都搜完了、只剩伸进没加载区块的边):那边是什么不知道。 */
        UNLOADED,
        /** 搜到了目标,到手的路却不完整(搜索期间世界变了、沿途有动作不再可行,或路在没加载的区块边上被截断)。 */
        CUT_SHORT
    }

    private final Type type;
    private final NavPath path;
    private final Stop stop;

    /** 取消或异常:没有路径,也谈不上搜索为什么停。 */
    public PathCalcResult(Type type) {
        this(type, null, null);
    }

    /** 直达目标。 */
    public PathCalcResult(Type type, NavPath path) {
        this(type, path, null);
    }

    /**
     * @param stop 没到目标的结论(部分路径段、没找到)必带,其余必须为 null
     */
    public PathCalcResult(Type type, NavPath path, Stop stop) {
        this.type = type;
        this.path = path;
        this.stop = stop;
        boolean success = type == Type.SUCCESS_TO_GOAL || type == Type.SUCCESS_SEGMENT;
        if (success && path == null) {
            throw new IllegalArgumentException("成功结果必须带路径");
        }
        if (!success && path != null) {
            throw new IllegalArgumentException("非成功结果不得带路径");
        }
        boolean unreached = type == Type.SUCCESS_SEGMENT || type == Type.FAILURE;
        if (unreached != (stop != null)) {
            throw new IllegalArgumentException("没到目标的结论必须说清为什么停,其余不得带");
        }
    }

    public Type getType() {
        return type;
    }

    public Optional<NavPath> getPath() {
        return Optional.ofNullable(path);
    }

    /** 没到目标时搜索为什么停;到了目标、取消、异常为 null。 */
    public Stop stop() {
        return stop;
    }
}
