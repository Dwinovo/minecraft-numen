package com.dwinovo.numen.core.pathing.astar;

import java.util.Optional;

/** 一次路径计算的结论:结果类型 + 可空的路径。 */
public final class PathCalcResult {

    public enum Type {
        /** 找到直达目标的完整路径。 */
        SUCCESS_TO_GOAL,
        /** 找到朝目标推进的部分路径段。 */
        SUCCESS_SEGMENT,
        /** 计算被协作取消。 */
        CANCELLATION,
        /** 搜索烧尽预算仍无可用路径。 */
        FAILURE,
        /** 计算过程抛出异常。 */
        EXCEPTION
    }

    private final Type type;
    private final NavPath path;

    public PathCalcResult(Type type) {
        this(type, null);
    }

    public PathCalcResult(Type type, NavPath path) {
        this.type = type;
        this.path = path;
        boolean success = type == Type.SUCCESS_TO_GOAL || type == Type.SUCCESS_SEGMENT;
        if (success && path == null) {
            throw new IllegalArgumentException("成功结果必须带路径");
        }
        if (!success && path != null) {
            throw new IllegalArgumentException("非成功结果不得带路径");
        }
    }

    public Type getType() {
        return type;
    }

    public Optional<NavPath> getPath() {
        return Optional.ofNullable(path);
    }
}
