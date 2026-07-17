package com.dwinovo.numen.core.pathing.execute;

import java.util.EnumMap;

import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 输入/视角落地层:把移动原语每 tick 的输出(期望视角 + 按键表)落到
 * 服务端玩家实体上。
 *
 * <p>工作方式是"记录 + 提交":移动原语经 {@link Movement.ExecutionDelegate}
 * 四钩子把本 tick 的意图记进来,执行器随后还可覆写(强跳、接管疾跑等),
 * 每 tick 末尾 {@link #commit()} 一次性落地:
 * <ol>
 *   <li>视角:按鼠标像素量化步进逼近目标({@link AimProcessor}),直接写
 *       实体的 yaw/头 yaw/pitch。放置/挖掘的命中判定全部用步进后的实际
 *       视角做 raycast——视线没转到位,点击就不会开始;</li>
 *   <li>右键:沿实际视角原生 raycast,命中方块走 {@code gameMode.useItemOn}
 *       (放置/开门),未消费再落 {@code gameMode.useItem}(水桶放水),
 *       两手都试,间隔 rightClickSpeed tick;</li>
 *   <li>左键:交 {@link BlockDigger} 渐进挖掘(原生 handleBlockBreakAction
 *       通道,破块延迟由其内置);目标格由 {@link #beginBreaking} 记录,
 *       没有记录时打准星命中的方块(清障);</li>
 *   <li>移动键:前后 → zza、左右 → xxa(潜行冲量 ×0.3),方向按目标 yaw
 *       定义、折算到已应用的实际 yaw 帧;JUMP 走地面起跳/液体浮力语义;
 *       SNEAK 逐 tick 写 shift 状态。</li>
 * </ol>
 * SPRINT 键刻意不落地——疾跑由执行器整体决策后直接 setSprinting。
 * 按键表跨 tick 保留,由移动原语每 tick 清空重设(执行器的强制键因此
 * 能存活到下一次移动原语更新)。
 */
public final class ExecHarness implements Movement.ExecutionDelegate {

    /** 右键尝试的两只手,主手优先。 */
    private static final InteractionHand[] HANDS = {InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND};

    private final NumenPlayer player;
    private final AimProcessor aim;
    private final BlockDigger digger;

    /** 本 tick 的按键表(跨 tick 保留,移动原语每 tick 清空重设)。 */
    private final EnumMap<Input, Boolean> keys = new EnumMap<>(Input.class);

    /** 本 tick 的期望视角;commit 后即失效。 */
    private MovementState.MovementTarget target;

    /** beginBreaking 记录的挖掘目标格。 */
    private BlockPos breakTarget;

    /** 距下一次允许右键的 tick 数。 */
    private int rightClickCooldown;

    /** 本 tick 是否有任何记录待落地。 */
    private boolean dirty;

    public ExecHarness(NumenPlayer player) {
        this.player = player;
        this.aim = new AimProcessor();
        this.digger = new BlockDigger(player);
    }

    // ==================== ExecutionDelegate 四钩子 ====================

    /**
     * 开挖一格:找该格的可视瞄点(形状中心 + 六面心逐一试射线),把
     * 视角目标设过去;实际视角的射线已命中该格(或角度已贴住目标)
     * 才按左键——视线步进决定挖掘的起始时机。完全不可视时直接瞄方块
     * 中心强挖(射线打中什么破什么,遮挡回退由挖掘器处理)。
     */
    @Override
    public void beginBreaking(MovementState state, BlockPos pos) {
        dirty = true;
        Vec3 eye = player.getEyePosition();
        Vec3 aimPoint = reachableAimPoint(pos);
        if (aimPoint != null) {
            state.setTarget(new MovementState.MovementTarget(
                    MovementHelper.yawTo(eye, aimPoint),
                    MovementHelper.pitchTo(eye, aimPoint), true));
            if (isLookingAt(pos) || isFacingTarget(state.getTarget())) {
                state.setInput(Input.CLICK_LEFT, true);
                breakTarget = pos.immutable();
            }
        } else {
            Vec3 center = MovementHelper.blockCenter(pos);
            state.setTarget(new MovementState.MovementTarget(
                    MovementHelper.yawTo(eye, center),
                    MovementHelper.pitchTo(eye, center), true));
            state.setInput(Input.CLICK_LEFT, true);
            breakTarget = pos.immutable();
        }
    }

    @Override
    public void applyRotation(MovementState.MovementTarget target) {
        this.target = target;
        dirty = true;
    }

    @Override
    public void clearInputs() {
        keys.clear();
        dirty = true;
    }

    @Override
    public void applyInput(Input input, boolean held) {
        keys.put(input, held);
        dirty = true;
    }

    // ==================== 执行器覆写面 ====================

    /** 某键当前是否被请求按下。 */
    public boolean isKeyRequested(Input input) {
        return keys.getOrDefault(input, false);
    }

    /** 强制设键(执行器的疾跑接管、直跳强按等)。 */
    public void forceKey(Input input, boolean held) {
        keys.put(input, held);
        dirty = true;
    }

    /**
     * 清空全部按键并立即停住身体(输入字段清零、松疾跑、松潜行)。
     * 取消/暂停路径时用;不打断进行中的挖掘(那是 {@link #stopBreaking})。
     */
    public void clearAllKeys() {
        keys.clear();
        target = null;
        breakTarget = null;
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }

    /** 中止进行中的挖掘(服务端 ABORT + 清裂纹)。 */
    public void stopBreaking() {
        digger.cancel();
        breakTarget = null;
    }

    /** 是否有进行中的挖掘(liveness 记账:挖硬方块也是真实推进)。 */
    public boolean isDigging() {
        return digger.current() != null;
    }

    /** 视角步进量化器(执行器做放置预判时共用同一套数学)。 */
    public AimProcessor aimProcessor() {
        return aim;
    }

    // ==================== 提交 ====================

    /** 有记录待落地时提交一次;无记录跳过(不打扰别的驱动方)。 */
    public void commitIfDirty() {
        if (dirty) {
            commit();
        }
    }

    /**
     * 把本 tick 的记录落到实体上。顺序:视角步进 → 右键(用步进后的
     * 实际视角 raycast)→ 左键挖掘 → 恢复步进视角(挖掘器内部会把
     * 视线吸到命中点,视角所有权归步进器)→ 移动输入字段。
     */
    public void commit() {
        dirty = false;
        MovementState.MovementTarget t = target;
        target = null;

        AimProcessor.Rotation stepped = null;
        if (t != null && t.hasRotation()) {
            stepped = aim.step(player.getYRot(), player.getXRot(), t.getYaw(), t.getPitch());
            applyLook(stepped);
        }

        if (rightClickCooldown > 0) {
            rightClickCooldown--;
        }
        if (isKeyRequested(Input.CLICK_RIGHT) && rightClickCooldown == 0) {
            rightClick();
            rightClickCooldown = NavSettings.get().rightClickSpeed;
        }

        if (isKeyRequested(Input.CLICK_LEFT)) {
            BlockPos digPos = breakTarget != null ? breakTarget : crosshairBlock();
            if (digPos != null) {
                digger.digStep(digPos);
            }
            if (stepped != null) {
                applyLook(stepped);
            }
        } else {
            breakTarget = null;
            if (digger.current() != null) {
                digger.cancel();
            }
        }

        float forward = (isKeyRequested(Input.MOVE_FORWARD) ? 1.0f : 0.0f)
                + (isKeyRequested(Input.MOVE_BACK) ? -1.0f : 0.0f);
        float strafe = (isKeyRequested(Input.MOVE_LEFT) ? 1.0f : 0.0f)
                + (isKeyRequested(Input.MOVE_RIGHT) ? -1.0f : 0.0f);
        boolean sneak = isKeyRequested(Input.SNEAK);
        if (sneak) {
            forward *= 0.3f;
            strafe *= 0.3f;
        }
        // 移动方向按目标 yaw;无视角目标时就按实体当前 yaw
        float moveYaw = (t != null && t.hasRotation()) ? t.getYaw() : player.getYRot();
        float[] impulse = AimProcessor.remapInput(strafe, forward, moveYaw, player.getYRot());
        player.xxa = impulse[0];
        player.zza = impulse[1];
        player.setShiftKeyDown(sneak);
        if (isKeyRequested(Input.JUMP)) {
            InputDriver.jump(player);
        }
    }

    private void applyLook(AimProcessor.Rotation rotation) {
        player.setYRot(rotation.yaw());
        player.setYHeadRot(rotation.yaw());
        player.setXRot(rotation.pitch());
    }

    // ==================== 右键 ====================

    /**
     * 一次右键:沿实际视角原生 raycast,命中方块 → useItemOn(放置/
     * 开门/交互);未消费 → useItem(手上物品自决,水桶放水走这里)。
     * 两手按主手优先逐一试,消费即挥手结束。
     */
    private void rightClick() {
        Level level = player.level();
        BlockHitResult hit = clipAlongView();
        for (InteractionHand hand : HANDS) {
            ItemStack stack = player.getItemInHand(hand);
            if (hit.getType() == HitResult.Type.BLOCK) {
                InteractionResult res = player.gameMode.useItemOn(player, level, stack, hand, hit);
                if (res.consumesAction()) {
                    player.swing(hand);
                    return;
                }
                if (res == InteractionResult.FAIL) {
                    return;
                }
            }
            if (!stack.isEmpty()) {
                InteractionResult res = player.gameMode.useItem(player, level, stack, hand);
                if (res.consumesAction()) {
                    player.swing(hand);
                    return;
                }
                if (res == InteractionResult.FAIL) {
                    return;
                }
            }
        }
    }

    // ==================== 视线判定 ====================

    /** 实际视角的射线是否命中该格。 */
    public boolean isLookingAt(BlockPos pos) {
        BlockHitResult hit = clipAlongView();
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** 实际视角是否已贴住目标转角(角度容差 0.01°;量化残差下很少成立,主判据是射线)。 */
    public boolean isFacingTarget(MovementState.MovementTarget target) {
        if (!target.hasRotation()) {
            return false;
        }
        return Math.abs(AimProcessor.normalizeDelta(player.getYRot() - target.getYaw())) < 0.01
                && Math.abs(player.getXRot() - target.getPitch()) < 0.01;
    }

    /** 准星此刻命中的方块;未命中返回 null。 */
    public BlockPos crosshairBlock() {
        BlockHitResult hit = clipAlongView();
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /** 沿实体当前视角的轮廓射线(不穿流体)。 */
    private BlockHitResult clipAlongView() {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(NavSettings.get().blockReachDistance));
        return player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /**
     * 该格上眼睛能实际射到的第一个瞄点(形状中心优先,再六面心);
     * 全部被挡返回 null。
     */
    private Vec3 reachableAimPoint(BlockPos pos) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = NavSettings.get().blockReachDistance;
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        Vec3[] aims = {
                pointOn(pos, shape, 0.5, 0.5, 0.5),
                pointOn(pos, shape, 0.5, 0.0, 0.5),
                pointOn(pos, shape, 0.5, 1.0, 0.5),
                pointOn(pos, shape, 0.5, 0.5, 0.0),
                pointOn(pos, shape, 0.5, 0.5, 1.0),
                pointOn(pos, shape, 0.0, 0.5, 0.5),
                pointOn(pos, shape, 1.0, 0.5, 0.5),
        };
        for (Vec3 aimPoint : aims) {
            Vec3 dir = aimPoint.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) {
                continue;
            }
            Vec3 end = eye.add(dir.normalize().scale(reach));
            BlockHitResult res = level.clip(new ClipContext(
                    eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (res.getType() == HitResult.Type.BLOCK && res.getBlockPos().equals(pos)) {
                return res.getLocation();
            }
        }
        return null;
    }

    /** 方块碰撞形状上按比例取点(m 为各轴的 min↔max 插值系数)。 */
    private static Vec3 pointOn(BlockPos pos, VoxelShape shape, double mx, double my, double mz) {
        double x = shape.min(Direction.Axis.X) * mx + shape.max(Direction.Axis.X) * (1 - mx);
        double y = shape.min(Direction.Axis.Y) * my + shape.max(Direction.Axis.Y) * (1 - my);
        double z = shape.min(Direction.Axis.Z) * mz + shape.max(Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    // ==================== 换手 / 备货 ====================

    /**
     * 保证快捷栏里有可垫路耗材:快捷栏已有则不动,背包深处有则换进
     * 当前选中格(规划期按全背包判有料,执行期在这里兑现)。
     */
    public boolean ensureThrowawayInHotbar() {
        var acceptable = NavSettings.get().acceptableThrowawayItems();
        return ensureInHotbar(stack -> !stack.isEmpty() && acceptable.contains(stack.getItem()));
    }

    /** 保证快捷栏里有水桶(坠落接水前备货)。 */
    public boolean ensureWaterBucketInHotbar() {
        return ensureInHotbar(stack -> stack.is(Items.WATER_BUCKET));
    }

    private boolean ensureInHotbar(java.util.function.Predicate<ItemStack> what) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (what.test(inv.getItem(i))) {
                return true;
            }
        }
        for (int i = 9; i < inv.items.size(); i++) {
            if (what.test(inv.getItem(i))) {
                player.holdInHand(i);
                return true;
            }
        }
        return false;
    }
}
