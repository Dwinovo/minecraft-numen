package com.dwinovo.numen.pathing.search;

import java.util.Objects;

import com.dwinovo.numen.pathing.plan.CostModel;

import net.minecraft.core.BlockPos;

/**
 * 一次搜索要的全部输入:读哪份世界、按哪份成本模型、从哪个节点出发、去哪、最多展开几个节点、沿不沿旧路。
 *
 * @param start  起点节点,由 {@link Origin} 从身体的真实位置定出
 * @param budget 最多展开几个节点;结论不随机器快慢和 tick 速率变
 */
public record Search(SearchView view, CostModel model, BlockPos start, Goal goal, int budget, Favoring favoring) {

    public Search {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(favoring, "favoring");
        start = start.immutable();
        if (budget <= 0) {
            throw new IllegalArgumentException("展开预算要是正数:" + budget);
        }
    }
}
