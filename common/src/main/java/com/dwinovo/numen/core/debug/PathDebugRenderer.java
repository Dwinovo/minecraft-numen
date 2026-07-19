package com.dwinovo.numen.core.debug;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.execute.PathingCore;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.goals.GoalBlock;
import com.dwinovo.numen.core.pathing.goals.GoalComposite;
import com.dwinovo.numen.core.pathing.goals.GoalGetToBlock;
import com.dwinovo.numen.core.pathing.goals.GoalInverted;
import com.dwinovo.numen.core.pathing.goals.GoalTwoBlocks;
import com.dwinovo.numen.core.pathing.goals.GoalXZ;
import com.dwinovo.numen.network.payload.PathDebugPayload;
import com.dwinovo.numen.platform.Services;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路调试状态发布:每 {@link #INTERVAL} tick 把每个活跃寻路内核的
 * 状态(当前段/下一段/在飞最优路径、待挖/待放/挤身格集、目标)打包成
 * {@link PathDebugPayload} 发给同维度开了调试的主人;客户端逐帧画成
 * 世界空间的线与方框。不产生任何粒子。
 */
public final class PathDebugRenderer {

    private static final int INTERVAL = 5;

    private static int tickCounter;

    private PathDebugRenderer() {}

    public static void serverTick(MinecraftServer server) {
        if (!PathDebug.anyEnabled()) {
            return;
        }
        if (++tickCounter % INTERVAL != 0) {
            return;
        }
        for (PathingCore core : PathingCore.liveCores()) {
            ServerLevel level = (ServerLevel) core.player().level();
            List<ServerPlayer> viewers = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (PathDebug.isEnabled(p.getUUID()) && p.level() == level) {
                    viewers.add(p);
                }
            }
            if (viewers.isEmpty()) {
                continue;
            }
            PathDebugPayload payload = snapshot(core);
            for (ServerPlayer viewer : viewers) {
                Services.NETWORK.sendToPlayer(viewer, payload);
            }
        }
    }

    /** 采集一个内核此刻的可视状态(空状态也发,客户端据此清屏)。 */
    private static PathDebugPayload snapshot(PathingCore core) {
        List<Long> currentPath = new ArrayList<>();
        List<Long> nextPath = new ArrayList<>();
        List<Long> bestPath = new ArrayList<>();
        List<Long> toBreak = new ArrayList<>();
        List<Long> toPlace = new ArrayList<>();
        List<Long> toWalkInto = new ArrayList<>();
        List<Long> goalBoxes = new ArrayList<>();
        List<Long> goalColumns = new ArrayList<>();

        PathExecutor current = core.getCurrent();
        if (current != null) {
            // 与观察端习惯一致:当前段从已推进位置往前三格开始描
            packPath(current.getPath(), Math.max(0, current.getPosition() - 3), currentPath);
            for (BlockPos pos : current.toBreak()) {
                toBreak.add(pos.asLong());
            }
            for (BlockPos pos : current.toPlace()) {
                toPlace.add(pos.asLong());
            }
            for (BlockPos pos : current.toWalkInto()) {
                toWalkInto.add(pos.asLong());
            }
        }
        PathExecutor next = core.getNext();
        if (next != null) {
            packPath(next.getPath(), 0, nextPath);
        }
        core.inProgressBestPath().ifPresent(best -> packPath(best, 0, bestPath));
        packGoal(core.getGoal(), goalBoxes, goalColumns);

        return new PathDebugPayload(core.player().getUUID(),
                currentPath, nextPath, bestPath, toBreak, toPlace, toWalkInto,
                goalBoxes, goalColumns);
    }

    private static void packPath(NavPath path, int startIndex, List<Long> out) {
        List<BlockPos> positions = path.positions();
        for (int i = startIndex; i < positions.size(); i++) {
            out.add(positions.get(i).asLong());
        }
    }

    private static void packGoal(Goal goal, List<Long> boxes, List<Long> columns) {
        switch (goal) {
            case null -> { }
            case GoalBlock g -> boxes.add(g.getGoalPos().asLong());
            case GoalTwoBlocks g -> {
                boxes.add(g.getGoalPos().asLong());
                boxes.add(g.getGoalPos().above().asLong());
            }
            case GoalGetToBlock g -> boxes.add(g.getGoalPos().asLong());
            case GoalXZ g -> columns.add(BlockPos.asLong(g.x, 0, g.z));
            case GoalComposite g -> {
                for (Goal sub : g.goals()) {
                    packGoal(sub, boxes, columns);
                }
            }
            case GoalInverted g -> packGoal(g.origin, boxes, columns);
            default -> { }
        }
    }
}
