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

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3f;

/**
 * 寻路调试粒子渲染:向开了调试的主人玩家发送 dust 粒子,勾勒当前段
 * 路径(红)、下一段(品红)、在飞搜索最优部分路径(蓝)、待挖格(红框)、
 * 待放格(绿框)、挤身格(品红框)与目标(绿框)。每 {@link #INTERVAL}
 * tick 发一轮,只发给与同伴同维度的调试玩家。
 */
public final class PathDebugRenderer {

    private static final int INTERVAL = 3;
    /** 路径线上相邻格心之间的插值步长(格)。 */
    private static final double LINE_STEP = 0.75;

    private static final Vector3f RED = new Vector3f(1.0f, 0.1f, 0.1f);
    private static final Vector3f MAGENTA = new Vector3f(1.0f, 0.2f, 1.0f);
    private static final Vector3f BLUE = new Vector3f(0.2f, 0.4f, 1.0f);
    private static final Vector3f GREEN = new Vector3f(0.1f, 1.0f, 0.1f);

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
            renderCore(core, level, viewers);
        }
    }

    private static void renderCore(PathingCore core, ServerLevel level, List<ServerPlayer> viewers) {
        PathExecutor current = core.getCurrent();
        if (current != null) {
            // 与观察端习惯一致:当前段从已推进位置往前三格开始描
            drawPath(level, viewers, current.getPath(), Math.max(0, current.getPosition() - 3), RED);
            for (BlockPos pos : current.toBreak()) {
                drawBlockBox(level, viewers, pos, RED);
            }
            for (BlockPos pos : current.toPlace()) {
                drawBlockBox(level, viewers, pos, GREEN);
            }
            for (BlockPos pos : current.toWalkInto()) {
                drawBlockBox(level, viewers, pos, MAGENTA);
            }
        }
        PathExecutor next = core.getNext();
        if (next != null) {
            drawPath(level, viewers, next.getPath(), 0, MAGENTA);
        }
        core.inProgressBestPath().ifPresent(best -> drawPath(level, viewers, best, 0, BLUE));
        drawGoal(level, viewers, core.getGoal());
    }

    private static void drawPath(ServerLevel level, List<ServerPlayer> viewers,
                                 NavPath path, int startIndex, Vector3f color) {
        List<BlockPos> positions = path.positions();
        for (int i = startIndex; i < positions.size() - 1; i++) {
            BlockPos a = positions.get(i);
            BlockPos b = positions.get(i + 1);
            double ax = a.getX() + 0.5, ay = a.getY() + 0.5, az = a.getZ() + 0.5;
            double dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), dz = b.getZ() - a.getZ();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(1, (int) (len / LINE_STEP));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                emit(level, viewers, color, ax + dx * t, ay + dy * t, az + dz * t);
            }
        }
        if (!positions.isEmpty()) {
            BlockPos end = positions.get(positions.size() - 1);
            emit(level, viewers, color, end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5);
        }
    }

    /** 方块框:八个角点 + 顶面/底面边中点,勾出轮廓感。 */
    private static void drawBlockBox(ServerLevel level, List<ServerPlayer> viewers,
                                     BlockPos pos, Vector3f color) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cz = 0; cz <= 1; cz++) {
                    emit(level, viewers, color, x + cx, y + cy, z + cz);
                }
            }
        }
        for (int cy = 0; cy <= 1; cy++) {
            emit(level, viewers, color, x + 0.5, y + cy, z);
            emit(level, viewers, color, x + 0.5, y + cy, z + 1);
            emit(level, viewers, color, x, y + cy, z + 0.5);
            emit(level, viewers, color, x + 1, y + cy, z + 0.5);
        }
    }

    private static void drawGoal(ServerLevel level, List<ServerPlayer> viewers, Goal goal) {
        drawGoal(level, viewers, goal, GREEN);
    }

    private static void drawGoal(ServerLevel level, List<ServerPlayer> viewers, Goal goal, Vector3f color) {
        switch (goal) {
            case null -> { }
            case GoalBlock g -> drawBlockBox(level, viewers, g.getGoalPos(), color);
            case GoalTwoBlocks g -> {
                drawBlockBox(level, viewers, g.getGoalPos(), color);
                drawBlockBox(level, viewers, g.getGoalPos().above(), color);
            }
            case GoalGetToBlock g -> drawBlockBox(level, viewers, g.getGoalPos(), color);
            case GoalXZ g -> {
                // 无 Y 目标:在观察者脚下高度画一根短竖标
                for (ServerPlayer viewer : viewers) {
                    for (int dy = -2; dy <= 4; dy++) {
                        emit(level, List.of(viewer), color,
                                g.x + 0.5, viewer.getY() + dy, g.z + 0.5);
                    }
                }
            }
            case GoalComposite g -> {
                for (Goal sub : g.goals()) {
                    drawGoal(level, viewers, sub, color);
                }
            }
            case GoalInverted g -> drawGoal(level, viewers, g.origin, RED);
            default -> { }
        }
    }

    private static void emit(ServerLevel level, List<ServerPlayer> viewers,
                             Vector3f color, double x, double y, double z) {
        DustParticleOptions dust = new DustParticleOptions(color, 0.6f);
        for (ServerPlayer viewer : viewers) {
            level.sendParticles(viewer, dust, true, x, y, z, 1, 0, 0, 0, 0);
        }
    }
}
