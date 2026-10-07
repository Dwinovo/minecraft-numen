package com.dwinovo.numen.pathing.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 够得着:身体以某个姿势站在某处,手能不能碰到某一格或某个碰撞箱。挖、放、交互、近战出手,"挖"与"够着"目标的到达都读这一个判据。
 *
 * <p>口径照原版 {@code Player.canInteractWithBlock}:眼睛到这一格整块包围盒的最近距离小于交互距离,只是包围盒四面往里收
 * {@link #EDGE}——瞄准时({@code Aim})离棱留的那一圈边:这里说够得着,瞄面上离眼睛最近的那一点就一定在交互距离内,挖、放、用的
 * 一方站在这儿交得出瞄点;不收的话,够到包围盒棱角的站位会被说成够得着,真去瞄时却差这一点距离。交互距离取
 * {@link BodyStats#blockReach()},由调用方按身体的真实属性给(生存 4.5、创造 5)。原版服务端收包时在它上面另加 1 格宽限,
 * 那是容忍客户端的误差,不是身体真能伸到的地方,这里不加。这里只判几何上的距离,看不看得见在 {@link Sight}。
 */
public final class Reach {

    /** 瞄面上离眼睛最近的一点时离棱留的边:准星不落在两格共用的棱上。够不够得着也量到收了这一圈的盒子。 */
    public static final double EDGE = 0.05;

    private Reach() {}

    /** 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时眼睛的位置(列中心)。 */
    public static Vec3 eye(BodyStats body, Pose pose, int x, double feetY, int z) {
        return new Vec3(x + 0.5, feetY + body.eyeHeight(pose), z + 0.5);
    }

    /** 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时,够不够得着 {@code target} 这一格。 */
    public static boolean reaches(BodyStats body, Pose pose, int x, double feetY, int z, BlockPos target) {
        return reaches(body, pose, x, feetY, z, new AABB(target).deflate(EDGE), body.blockReach());
    }

    /**
     * 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时,够不够得着 {@code box} 这个碰撞箱:眼睛到它的最近距离小于
     * {@code range}。口径照原版 {@code Player.canInteractWithEntity},交互距离由调用方按身体的属性给(打实体是
     * {@code entity_interaction_range},原版 3);服务端收包时另加的宽限同样不加。
     */
    public static boolean reaches(BodyStats body, Pose pose, int x, double feetY, int z, AABB box, double range) {
        return box.distanceToSqr(eye(body, pose, x, feetY, z)) < range * range;
    }
}
