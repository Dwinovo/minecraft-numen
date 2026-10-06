package com.dwinovo.numen.client.consent;

import com.dwinovo.numen.network.payload.ConsentRequestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;

/**
 * 世界里给挂着的征询涉及的方块与实体描轮廓——主人抬头就知道她问的是哪几块、哪一只。
 * 只照 {@link ConsentCards} 画,请求撤回轮廓就没了。与寻路调试覆盖层同一条世界渲染通道:原版 gizmo,
 * 只能在 gizmo 收集器在位的作用域内调用(渲染帧内)。
 */
public final class ConsentOutlines {

    /** ARGB 琥珀色。 */
    private static final int AMBER = 0xFFFFB81A;
    /** 方块的轮廓比整格外扩 0.01,贴着方块面不被它遮住。 */
    private static final float BLOCK_OUTSET = 0.01f;
    /** 实体的轮廓比碰撞箱外扩 0.05。 */
    private static final double ENTITY_OUTSET = 0.05;

    private ConsentOutlines() {}

    /** 世界渲染钩子入口(gizmo 收集器在位的渲染帧作用域内)。 */
    public static void emit() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ConsentCards.all().isEmpty()) {
            return;
        }
        for (ConsentCards.Card card : ConsentCards.all()) {
            ConsentRequestPayload request = card.request();
            for (long packed : request.blocks()) {
                Gizmos.cuboid(BlockPos.of(packed), BLOCK_OUTSET, GizmoStyle.stroke(AMBER));
            }
            for (int id : request.entities()) {
                Entity entity = mc.level.getEntity(id);
                if (entity != null) {
                    Gizmos.cuboid(entity.getBoundingBox().inflate(ENTITY_OUTSET), GizmoStyle.stroke(AMBER));
                }
            }
        }
    }
}
