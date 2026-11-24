package com.dwinovo.numen.api.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 走到位的判据:身体以某个姿势站在某处,手够不够得着某一格或某个碰撞箱。寻路给"够着"一格、一只实体的目标挑站位,任务判该不该先
 * 走过去,读的都是这一个。
 *
 * <p>口径照原版 {@code Player.canInteractWithBlock}、{@code canInteractWithEntity}:眼睛到这一格整块包围盒(或那个碰撞箱)的最近
 * 距离小于交互距离。眼睛的位置与交互距离由调用方按身体的真实属性给(方块生存 4.5、创造 5,实体 3)。原版服务端收包时在它上面
 * 另加 1 格宽限({@link Mouse#reaches(BlockPos)}:按下去那一刻查的是加了宽限的),那是容忍身体停得不正、还在挪的余量,挑站位时
 * 不加。只判距离,不问中间隔着什么。
 */
public final class Reach {

    private Reach() {}

    /** 眼睛在 {@code eye} 时,够不够得着 {@code target} 这一格:眼睛到它整块包围盒的最近距离小于 {@code range}(方块交互距离)。 */
    public static boolean reaches(Vec3 eye, BlockPos target, double range) {
        return reaches(eye, new AABB(target), range);
    }

    /** 眼睛在 {@code eye} 时,够不够得着 {@code box} 这个碰撞箱:眼睛到它的最近距离小于 {@code range}。 */
    public static boolean reaches(Vec3 eye, AABB box, double range) {
        return box.distanceToSqr(eye) < range * range;
    }
}
