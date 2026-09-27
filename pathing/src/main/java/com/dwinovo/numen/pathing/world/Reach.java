package com.dwinovo.numen.pathing.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 够得着:身体以某个姿势站在某处,手能不能碰到某一格。挖、放、交互、"贴脸"目标的到达都读这一个判据。
 *
 * <p>口径照原版 {@code Player.canInteractWithBlock}:眼睛到这一格整块包围盒的最近距离小于交互距离。交互距离取
 * {@link BodyStats#blockReach()},由调用方按身体的真实属性给(生存 4.5、创造 5)。原版服务端收包时在它上面另加 1 格宽限,
 * 那是容忍客户端的误差,不是身体真能伸到的地方,这里不加。这里只判几何上的距离,视线由执行层到场复核。
 */
public final class Reach {

    private Reach() {}

    /** 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时眼睛的位置(列中心)。 */
    public static Vec3 eye(BodyStats body, Pose pose, int x, double feetY, int z) {
        return new Vec3(x + 0.5, feetY + body.eyeHeight(pose), z + 0.5);
    }

    /** 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时,够不够得着 {@code target} 这一格。 */
    public static boolean reaches(BodyStats body, Pose pose, int x, double feetY, int z, BlockPos target) {
        double range = body.blockReach();
        return new AABB(target).distanceToSqr(eye(body, pose, x, feetY, z)) < range * range;
    }
}
