package com.dwinovo.numen.core.pathing.astar;

import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;

/** 路径实现的公共截断逻辑:加载边界截断与部分路径截尾。 */
public abstract class PathBase implements NavPath {

    /**
     * 找到第一个落在未加载 chunk 里的格位并截到那里。
     * {@code cutoffAtLoadBoundary} 默认关,关闭时原样返回。
     */
    @Override
    public NavPath cutoffAtLoadedChunks(ChunkLoadedTest loadedTest) {
        if (!NavSettings.get().cutoffAtLoadBoundary) {
            return this;
        }
        for (int i = 0; i < positions().size(); i++) {
            BlockPos pos = positions().get(i);
            if (!loadedTest.isLoaded(pos.getX(), pos.getZ())) {
                return new CutoffPath(this, i);
            }
        }
        return this;
    }

    /**
     * 部分路径截尾:长度不足 {@code pathCutoffMinimumLength}(30)不截;
     * 终点已在目标内不截;否则按 {@code pathCutoffFactor}(0.9)截掉
     * 尾部一成——段尾质量差,反正会再规划。
     */
    @Override
    public NavPath staticCutoff(Goal destination) {
        int min = NavSettings.get().pathCutoffMinimumLength;
        if (length() < min) {
            return this;
        }
        BlockPos dest = getDest();
        if (destination == null || destination.isInGoal(dest.getX(), dest.getY(), dest.getZ())) {
            return this;
        }
        double factor = NavSettings.get().pathCutoffFactor;
        int newLength = (int) ((length() - min) * factor) + min - 1;
        return new CutoffPath(this, newLength);
    }
}
