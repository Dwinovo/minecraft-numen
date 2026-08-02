package com.dwinovo.numen.core.pathing.moves;

import com.dwinovo.numen.core.pathing.execute.AimProcessor;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 行走朝向几何:瞄点求解、视线射线、朝向角与"朝方块走"的输入落地。
 * 此前混在 {@code MovementHelper} 的方块判定库里——但这一族回答的是
 * "眼睛该看哪、身体该朝哪"而不是"这一格能不能走",被执行层
 * ({@code ExecHarness}/{@code PathExecutor})、挖掘({@code BlockDigger})
 * 与建造演出跨包共用,单独成类。
 */
public final class AimGeometry {

    private AimGeometry() {}

    /** 方块中心点。 */
    public static Vec3 blockCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * 该格上眼睛能实际射到的第一个瞄点;全部被遮挡返回 null。
     * 判定次序:
     * <ol>
     *   <li>沿当前实际视角的射线已命中该格 → 保持视线,直接返回命中点
     *       (已注视时不再回中,避免无谓转头);</li>
     *   <li>碰撞形状中心与六面心逐一试射:每个候选点先算理想转角,再按
     *       视角步进量化出"本 tick 实际能转到的转角",沿该转角射线——
     *       命中该格才算可达(没转到位的 tick 不误判可视)。</li>
     * </ol>
     * 触及距离取 {@link NavSettings#blockReachDistance}。
     */
    public static Vec3 reachableAimPoint(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        var level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = blockReachDistance(player);
        var state = level.getBlockState(pos);
        boolean fire = state.getBlock() instanceof BaseFireBlock;
        // 已注视捷径:沿当前视角的射线恰好命中该格才保持(严格等格)
        BlockHitResult looking = clipAlongRotation(player, player.getYRot(), player.getXRot(), reach);
        if (looking.getType() == HitResult.Type.BLOCK && looking.getBlockPos().equals(pos)) {
            return looking.getLocation();
        }
        // 首选取心:碰撞形状中点(无碰撞体退整格心);火取底面高度
        // (灭火看火的根部)。六面心按轮廓形状取(射线判定也是轮廓)。
        Vec3 center = collisionCenter(level, pos, state);
        VoxelShape outline = state.getShape(level, pos);
        if (outline.isEmpty()) {
            outline = Shapes.block();
        }
        Vec3[] aims = {
                center,
                shapePoint(pos, outline, 0.5, 0.0, 0.5),
                shapePoint(pos, outline, 0.5, 1.0, 0.5),
                shapePoint(pos, outline, 0.5, 0.5, 0.0),
                shapePoint(pos, outline, 0.5, 0.5, 1.0),
                shapePoint(pos, outline, 0.0, 0.5, 0.5),
                shapePoint(pos, outline, 1.0, 0.5, 0.5),
        };
        var aim = new AimProcessor();
        for (Vec3 aimPoint : aims) {
            Vec3 dir = aimPoint.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) {
                continue;
            }
            // 本 tick 实际能转到的视角,沿它试射;可达则返回候选点本身
            // (调用方以候选点为视角目标,后续 tick 向它收敛)
            var stepped = aim.step(player.getYRot(), player.getXRot(),
                    yawTo(eye, aimPoint), pitchTo(eye, aimPoint));
            BlockHitResult res = clipAlongRotation(player, stepped.yaw(), stepped.pitch(), reach);
            if (hitsTarget(res, pos, fire)) {
                return aimPoint;
            }
        }
        return null;
    }

    /** 命中判定:命中该格;目标是火时命中其下方支撑格也算(火焰轮廓极薄)。 */
    private static boolean hitsTarget(BlockHitResult res, BlockPos pos, boolean fire) {
        if (res.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return res.getBlockPos().equals(pos) || (fire && res.getBlockPos().equals(pos.below()));
    }

    /** 碰撞形状中点;无碰撞体取整格心;火把 y 压到格底(看火的根部)。 */
    public static Vec3 collisionCenter(net.minecraft.world.level.Level level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        double x = (shape.min(net.minecraft.core.Direction.Axis.X) + shape.max(net.minecraft.core.Direction.Axis.X)) / 2;
        double y = (shape.min(net.minecraft.core.Direction.Axis.Y) + shape.max(net.minecraft.core.Direction.Axis.Y)) / 2;
        double z = (shape.min(net.minecraft.core.Direction.Axis.Z) + shape.max(net.minecraft.core.Direction.Axis.Z)) / 2;
        if (state.getBlock() instanceof BaseFireBlock) {
            y = 0;
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    /** 方块触及距离:创造 5.0,生存按设置(默认 4.5)。 */
    public static double blockReachDistance(net.minecraft.server.level.ServerPlayer player) {
        return player.isCreative() ? 5.0 : NavSettings.get().blockReachDistance;
    }

    /** 从眼位沿给定 yaw/pitch 的轮廓射线(不穿流体);方向向量按原版 float 三角。 */
    private static BlockHitResult clipAlongRotation(net.minecraft.server.level.ServerPlayer player,
                                                    float yaw, float pitch, double reach) {
        Vec3 eye = player.getEyePosition();
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float h = net.minecraft.util.Mth.cos(g);
        float i = net.minecraft.util.Mth.sin(g);
        float j = net.minecraft.util.Mth.cos(f);
        float k = net.minecraft.util.Mth.sin(f);
        Vec3 dir = new Vec3(i * j, -k, h * j);
        Vec3 end = eye.add(dir.scale(reach));
        return player.level().clip(new net.minecraft.world.level.ClipContext(
                eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
    }

    /** 方块碰撞形状上按比例取点(m 为各轴的 min↔max 插值系数)。 */
    static Vec3 shapePoint(BlockPos pos, VoxelShape shape, double mx, double my, double mz) {
        double x = shape.min(net.minecraft.core.Direction.Axis.X) * mx
                + shape.max(net.minecraft.core.Direction.Axis.X) * (1 - mx);
        double y = shape.min(net.minecraft.core.Direction.Axis.Y) * my
                + shape.max(net.minecraft.core.Direction.Axis.Y) * (1 - my);
        double z = shape.min(net.minecraft.core.Direction.Axis.Z) * mz
                + shape.max(net.minecraft.core.Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    /** 从 from 看向 to 的 yaw(度,MC 朝向约定;用 Mth.atan2 多项式近似)。 */
    public static float yawTo(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        return (float) Math.toDegrees(net.minecraft.util.Mth.atan2(dx, -dz));
    }

    /** 从 from 看向 to 的 pitch(度,向下为正;用 Mth.atan2 多项式近似)。 */
    public static float pitchTo(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dy = from.y - to.y;
        double dz = from.z - to.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.toDegrees(net.minecraft.util.Mth.atan2(dy, horizontal));
    }

    /**
     * 朝目标方块走:yaw 对准方块中心、pitch 保持现状(不强制转头),
     * 并按住前进。
     */
    public static void moveTowards(Player player, MovementState state, BlockPos pos) {
        float yaw = yawTo(player.getEyePosition(), blockCenter(pos));
        state.setTarget(new MovementState.MovementTarget(yaw, player.getXRot(), false));
        state.setInput(Input.MOVE_FORWARD, true);
    }
}
