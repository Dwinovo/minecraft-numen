package com.dwinovo.numen.core.pathing.moves.movements;
import com.dwinovo.numen.core.pathing.settings.ThrowawayBlocks;
import com.dwinovo.numen.core.pathing.moves.AimGeometry;

import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 移动原语执行期的放置与视线共用逻辑:五贴面枚举、贴面中心瞄点、
 * 转速受限预判命中、耗材选取、视线命中判定。
 *
 * <p>视角推进用 AimProcessor 把理想目标转角折算成"这一 tick 头实际
 * 能转到哪"(受鼠标像素量化与转速上限约束)再 raytrace。需要单 tick
 * 大幅转头的放置候选(如 Pillar 空中低头看脚下、Parkour 切换看
 * dest.below)可能判定"这一 tick 还够不到"从而推迟到下一 tick 或换面。
 * 方块中心不可视时回退到方块碰撞形状的六面心做 raytrace(边角回退)。
 */
final class MovementPlacement {

    private MovementPlacement() {}

    /** 一次放置尝试的结论。 */
    enum PlaceResult {
        /** 视线已对准正确贴面,本 tick 可右键。 */
        READY_TO_PLACE,
        /** 找到可行贴面,已把目标转角写进 state,等转头。 */
        ATTEMPTING,
        /** 五个贴面都不可行。 */
        NO_OPTION
    }

    /** 放置贴面枚举顺序:四个水平向在前,DOWN 最后(不含 UP)。 */
    static final Direction[] HORIZONTALS_AND_DOWN = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    /** 潜行时的眼高(米)。 */
    private static final double SNEAK_EYE_HEIGHT = 1.27;

    /**
     * 视角推进量化器:与 ExecHarness 同一灵敏度(0.5),用于把放置可行性的
     * 理想目标转角折算成"这一 tick 头实际能转到哪"再 raytrace。单例即可,
     * 纯数学、无状态。
     */
    private static final com.dwinovo.numen.core.pathing.execute.AimProcessor AIM =
            new com.dwinovo.numen.core.pathing.execute.AimProcessor();

    /** 以玩家当前视角作为"当前转角"的便捷入口。 */
    static PlaceResult attemptToPlaceABlock(MovementState state, ServerPlayer player,
                                            BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
        return attemptToPlaceABlock(state, player, placeAt, preferDown, wouldSneak,
                player.getYRot(), player.getXRot());
    }

    /**
     * 尝试对 placeAt 找一个可行的放置贴面:先试直视 placeAt 本体
     * (可替换方块自带轮廓时能命中,中心不可视回退到六面心),再按
     * {@link #HORIZONTALS_AND_DOWN} 枚举五个贴面,要求贴面方块可贴、
     * 且沿"这一 tick 头实际能转到哪"的射线命中该贴面且命中面的邻格
     * 恰为 placeAt。preferDown=false 取第一个可行(水平优先),true 取
     * 最后一个(DOWN 优先,空中放置不必歪头)。
     *
     * <p>当前转角已命中正确目标 → READY_TO_PLACE(右键由调用方按);
     * 找到贴面但没对准 → ATTEMPTING;找不到 → NO_OPTION。
     * 没有可垫路耗材时置 UNREACHABLE 并返回 NO_OPTION。
     */
    static PlaceResult attemptToPlaceABlock(MovementState state, ServerPlayer player,
                                            BlockPos placeAt, boolean preferDown, boolean wouldSneak,
                                            float currentYaw, float currentPitch) {
        Level level = player.level();
        // 放置落点先过权限层(耗材还没选,按"放什么都一样"问):不许放就没有可行贴面,
        // 状态机按够不着收场、重新规划——成本模型同一份裁决早已把这格定成 INF,走到这里
        // 只可能是规划之后世界变了。
        if (!com.dwinovo.numen.permission.Permission.judge(
                (com.dwinovo.numen.entity.NumenPlayer) player,
                com.dwinovo.numen.permission.Action.place(placeAt, level.getBlockState(placeAt), null))
                .allowed()) {
            state.setStatus(MovementStatus.UNREACHABLE);
            return PlaceResult.NO_OPTION;
        }
        BuildPlacementRegistry.recordScaffold(player, placeAt);
        double reach = player.blockInteractionRange();
        Vec3 eye = eyePosition(player, wouldSneak);
        boolean found = false;
        BlockHitResult foundHit = null;
        float foundYaw = currentYaw;
        float foundPitch = currentPitch;

        // 直视 placeAt 本体(走到这一步说明该格必是可替换的):按瞄点次序逐一试,
        // 用 peek 后的实际转角做 raytrace。
        for (Vec3 aim : AimGeometry.aimPoints(level, placeAt, level.getBlockState(placeAt))) {
            float yaw = AimGeometry.yawTo(eye, aim);
            float pitch = AimGeometry.pitchTo(eye, aim);
            com.dwinovo.numen.core.pathing.execute.AimProcessor.Rotation peek =
                    AIM.step(currentYaw, currentPitch, yaw, pitch);
            BlockHitResult hit = rayTrace(player, eye, peek.yaw(), peek.pitch(), reach);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(placeAt)) {
                if (!selectForLocation(player, placeAt, hit, peek.yaw(), peek.pitch(), false)) {
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                state.setTarget(new MovementState.MovementTarget(yaw, pitch, true));
                found = true;
                foundHit = hit;
                foundYaw = peek.yaw();
                foundPitch = peek.pitch();
                break; // 直视本体只取第一个可行,无需 preferDown
            }
        }

        for (int i = 0; i < 5; i++) {
            BlockPos against = placeAt.relative(HORIZONTALS_AND_DOWN[i]);
            if (!MovementHelper.canPlaceAgainst(level, against)) {
                continue;
            }
            // 贴面中心:两格坐标的中点,落在共享面上
            double faceX = (placeAt.getX() + against.getX() + 1.0) * 0.5;
            double faceY = (placeAt.getY() + against.getY() + 0.5) * 0.5;
            double faceZ = (placeAt.getZ() + against.getZ() + 1.0) * 0.5;
            Vec3 face = new Vec3(faceX, faceY, faceZ);
            float yaw = AimGeometry.yawTo(eye, face);
            float pitch = AimGeometry.pitchTo(eye, face);
            // 转速受限:把理想目标转角折算成这一 tick 头实际能转到哪再 raytrace
            com.dwinovo.numen.core.pathing.execute.AimProcessor.Rotation peek =
                    AIM.step(currentYaw, currentPitch, yaw, pitch);
            BlockHitResult hit = rayTrace(player, eye, peek.yaw(), peek.pitch(), reach);
            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(against)
                    && hit.getBlockPos().relative(hit.getDirection()).equals(placeAt)) {
                if (!selectForLocation(player, placeAt, hit, peek.yaw(), peek.pitch(), false)) {
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                state.setTarget(new MovementState.MovementTarget(yaw, pitch, true));
                found = true;
                foundHit = hit;
                foundYaw = peek.yaw();
                foundPitch = peek.pitch();
                if (!preferDown) {
                    break; // 水平优先:第一个可行即取
                }
            }
        }

        // 当前转角已经命中正确目标 → 就绪
        BlockHitResult looking = rayTrace(player, eyePosition(player, wouldSneak), currentYaw, currentPitch, reach);
        if (looking.getType() == HitResult.Type.BLOCK) {
            BlockPos selected = looking.getBlockPos();
            if (selected.equals(placeAt)
                    || (MovementHelper.canPlaceAgainst(level, selected)
                            && selected.relative(looking.getDirection()).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                if (!selectForLocation(player, placeAt, looking, currentYaw, currentPitch, true)) {
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            selectForLocation(player, placeAt, foundHit, foundYaw, foundPitch, true);
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    // 选料只有一个出口:先按图纸挑精确材料(施工中的格子值得放对),挑不出就
    // 退回通用垫路料。两条路最终都落到 selectThrowaway——它取料问的 ThrowawayBlocks.take
    // 就是规划器"有料可垫"(ThrowawayBlocks.available)的那一处,于是"背包空着也能垫路"
    // 对两条路同时成立。
    //
    // 这条汇流是必需的,不是顺手:规划器认定"有料可垫",执行器若在某
    // 条支路上选不出料就报 UNREACHABLE,两边对同一动作各执一词,而重新规划的
    // 输入分毫未变——必然算出同一条路、再次夭折,规划器与执行器能对着掐到天
    // 荒地老。可行性判据必须只有一处真源。
    static boolean selectForLocation(ServerPlayer player, BlockPos placeAt, boolean select) {
        if (BuildPlacementRegistry.hasTarget(player, placeAt)
                && BuildPlacementRegistry.selectForLocation(player, placeAt, select)) {
            return true;
        }
        return selectThrowaway(player, select);
    }

    static boolean selectForLocation(ServerPlayer player, BlockPos placeAt, BlockHitResult hit,
                                     float yaw, float pitch, boolean select) {
        if (BuildPlacementRegistry.hasTarget(player, placeAt)
                && BuildPlacementRegistry.selectForLocation(player, placeAt, hit, yaw, pitch, select)) {
            return true;
        }
        return selectThrowaway(player, select);
    }
    /**
     * 找垫路料并(可选)切到手上。取料只问 {@link ThrowawayBlocks#take}——规划器的"有没有料可垫"
     * ({@link ThrowawayBlocks#available})与它读同一份清单、同一个找法,免耗材画像变出来的料也是它认下的那种。
     */
    static boolean selectThrowaway(ServerPlayer player, boolean select) {
        ThrowawayBlocks.Source source = ThrowawayBlocks.take(player);
        if (source == null) {
            return false;
        }
        if (select) {
            source.select(player);
        }
        return true;
    }

    /** 玩家当前视线是否命中该方块(轮廓射线,不穿流体)。 */
    static boolean isLookingAt(ServerPlayer player, BlockPos pos) {
        BlockHitResult hit = rayTrace(player, player.getEyePosition(),
                player.getYRot(), player.getXRot(), player.blockInteractionRange());
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** 玩家当前视角是否已对准 state 中的目标转角(容差 0.01°)。 */
    static boolean isFacing(ServerPlayer player, MovementState.MovementTarget target) {
        if (!target.hasRotation()) {
            return false;
        }
        return Math.abs(normalizeDegrees(player.getYRot() - target.getYaw())) < 0.01
                && Math.abs(player.getXRot() - target.getPitch()) < 0.01;
    }

    /** 角度归一到 [-180, 180)。 */
    static float normalizeDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        return wrapped;
    }

    /** 沿指定转角从 eye 出发的轮廓射线(不含流体)。 */
    static BlockHitResult rayTrace(ServerPlayer player, Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 end = eye.add(direction(yaw, pitch).scale(reach));
        return player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /** 转角 → 单位视线向量。 */
    static Vec3 direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    /** 眼位;wouldSneak 时按潜行眼高取(提前用放置那一刻的视角算贴面)。 */
    static Vec3 eyePosition(ServerPlayer player, boolean wouldSneak) {
        if (wouldSneak) {
            return new Vec3(player.getX(), player.getY() + SNEAK_EYE_HEIGHT, player.getZ());
        }
        return player.getEyePosition();
    }
}

