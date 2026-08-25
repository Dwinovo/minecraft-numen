package com.dwinovo.numen.client.debug;

import com.dwinovo.numen.network.payload.PathDebugPayload;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * 寻路调试世界渲染:把 {@link PathDebugState} 里的快照画成世界空间的
 * 线与方框——当前路径红、下一段品红、在飞最优蓝、待挖红框、待放绿框、
 * 挤身品红框、目标绿框、x/z 目标画通天竖线。走原版 gizmo 调试图元通道
 * (26.2 移除立即模式 BufferSource,世界空间调试绘制统一收进 {@link Gizmos}
 * 静态收集器):逐帧提交,零粒子,默认深度测试(线条会被地形遮挡)。
 * 只能在 gizmo 收集器在位的作用域内调用(渲染帧内,两翼的
 * before-gizmos 挂点都满足),否则 {@link Gizmos} 直接抛状态异常。
 */
public final class PathDebugRenderer {

    // ARGB,alpha 0.9(0xE6)。
    private static final int RED = 0xE6FF2626;
    private static final int MAGENTA = 0xE6FF40FF;
    private static final int BLUE = 0xE64073FF;
    private static final int GREEN = 0xE626FF26;

    /** 方框线框比整格内缩 0.02,相邻格的框不重叠。 */
    private static final float BOX_INSET = -0.02f;

    private PathDebugRenderer() {}

    /** 世界渲染钩子入口(gizmo 收集器在位的渲染帧作用域内)。 */
    public static void emit() {
        List<PathDebugPayload> snapshots = PathDebugState.live();
        if (snapshots.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();
        for (PathDebugPayload p : snapshots) {
            polyline(p.currentPath(), RED);
            polyline(p.nextPath(), MAGENTA);
            polyline(p.bestPath(), BLUE);
            for (long packed : p.toBreak()) {
                box(packed, RED);
            }
            for (long packed : p.toPlace()) {
                box(packed, GREEN);
            }
            for (long packed : p.toWalkInto()) {
                box(packed, MAGENTA);
            }
            for (long packed : p.goalBoxes()) {
                box(packed, GREEN);
            }
            for (long packed : p.goalColumns()) {
                BlockPos pos = BlockPos.of(packed);
                Gizmos.line(
                        new Vec3(pos.getX() + 0.5, minY, pos.getZ() + 0.5),
                        new Vec3(pos.getX() + 0.5, maxY, pos.getZ() + 0.5), GREEN);
            }
        }
    }

    /** 折线:相邻格心连线段。 */
    private static void polyline(List<Long> packedPositions, int argb) {
        for (int i = 0; i + 1 < packedPositions.size(); i++) {
            BlockPos a = BlockPos.of(packedPositions.get(i));
            BlockPos b = BlockPos.of(packedPositions.get(i + 1));
            Gizmos.line(
                    new Vec3(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5),
                    new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5), argb);
        }
    }

    /** 线框盒:整格内缩后描边。 */
    private static void box(long packed, int argb) {
        Gizmos.cuboid(BlockPos.of(packed), BOX_INSET, GizmoStyle.stroke(argb));
    }
}
