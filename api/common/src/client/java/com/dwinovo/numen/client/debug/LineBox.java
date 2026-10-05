package com.dwinovo.numen.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 世界空间的线与线框,画在原版 lines 管线上。1.21.11 起 {@code ShapeRenderer} 不再提供 {@code renderLineBox},
 * 线框的 12 条棱各走一条 {@link #seg}。寻路调试覆盖层与征询轮廓共用这一处。
 */
public final class LineBox {

    private LineBox() {}

    /** 线框:12 条棱,坐标是 {@code pose} 所在空间里的绝对位置。 */
    public static void draw(VertexConsumer vc, PoseStack.Pose pose,
                            double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a) {
        seg(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        seg(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        seg(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        seg(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);
        seg(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        seg(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        seg(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        seg(vc, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);
        seg(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        seg(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        seg(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        seg(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    /** 一条线段(法线取线段方向,lines 渲染管线要求)。 */
    public static void seg(VertexConsumer vc, PoseStack.Pose pose,
                           double x1, double y1, double z1, double x2, double y2, double z2,
                           float r, float g, float b, float a) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5f) {
            return;
        }
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
    }
}
