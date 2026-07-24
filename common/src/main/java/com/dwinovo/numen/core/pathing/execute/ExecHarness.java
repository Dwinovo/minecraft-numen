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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
 *   <li>右键:沿实际视角原生 raycast,命中方块且不在使用物品中才走
 *       {@code gameMode.useItemOn}(放置/开门),两手都试,间隔
 *       rightClickSpeed tick;未命中方块不触发(不对空挥右键);</li>
 *   <li>左键:交 {@link BlockDigger} 渐进挖掘(原生 handleBlockBreakAction
 *       通道,破块延由其内置);挖掘只由准星驱动——射线命中什么挖什么,
 *       落空的 tick 不挖;同 tick 左键被按下时右键强制丢弃(破坏优先);</li>
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
        // 每 tick 按意图格选最优工具(遮挡回退挖到别的方块时仍持意图格
        // 的工具;实际落地的挖掘不再改选)
        com.dwinovo.numen.core.task.base.ToolSelect.holdBestTool(
                player, player.level().getBlockState(pos));
        Vec3 eye = player.getEyePosition();
        Vec3 aimPoint = reachableAimPoint(pos);
        if (aimPoint != null) {
            state.setTarget(new MovementState.MovementTarget(
                    MovementHelper.yawTo(eye, aimPoint),
                    MovementHelper.pitchTo(eye, aimPoint), true));
            if (isLookingAt(pos) || isFacingTarget(state.getTarget())) {
                state.setInput(Input.CLICK_LEFT, true);
            }
        } else {
            // 完全不可视:瞄整格中心强按左键——实际挖到的是准星命中的
            // 遮挡物(左键落地始终以准星射线为准)
            Vec3 center = MovementHelper.blockCenter(pos);
            state.setTarget(new MovementState.MovementTarget(
                    MovementHelper.yawTo(eye, center),
                    MovementHelper.pitchTo(eye, center), true));
            state.setInput(Input.CLICK_LEFT, true);
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
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }

    /** 中止进行中的挖掘(服务端 ABORT + 清裂纹)。 */
    public void stopBreaking() {
        digger.cancel();
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

    /** 有记录待落地时提交一次;无记录只递减右键冷却(冷却按游戏刻走)。 */
    public void commitIfDirty() {
        if (dirty) {
            commit();
        } else {
            if (rightClickCooldown > 0) {
                rightClickCooldown--;
            }
            digger.tickCooldown();
        }
    }

    /**
     * 把本 tick 的记录落到实体上。顺序:左右键点击(用上一 tick 已落地
     * 的视角做射线——点击相位先于本 tick 转头)→ 视角步进(覆盖挖掘器
     * 内部的临时看向,视角所有权归步进器)→ 移动输入字段。
     */
    public void commit() {
        dirty = false;
        MovementState.MovementTarget t = target;
        target = null;
        // 步进起点在点击相位前取样:挖掘器内部会把视线临时吸到命中点,
        // 步进必须仍从上一 tick 已落地的视角转起
        float startYaw = player.getYRot();
        float startPitch = player.getXRot();

        // 破坏优先:同 tick 左键被按下时,右键强制丢弃
        if (isKeyRequested(Input.CLICK_LEFT)) {
            keys.put(Input.CLICK_RIGHT, false);
        }
        // 冷却 tick 只倒数不尝试;冷却清零后,只有"真的发起了一次点击
        // 尝试"(准星命中方块)才重新扣冷却——准星未到位的空转 tick
        // 不吃冷却,命中的第一个 tick 即刻点击
        if (rightClickCooldown > 0) {
            rightClickCooldown--;
        } else if (isKeyRequested(Input.CLICK_RIGHT)) {
            rightClickTick();
        }

        boolean digTicked = false;
        if (isKeyRequested(Input.CLICK_LEFT)) {
            // 挖掘只由准星驱动:射线命中什么挖什么(命中位置与命中面
            // 直通挖掘器);落空或被实体挡住的 tick 不产生破坏动作,且
            // 进行中的挖掘取消归零——遮挡消失后从头重挖,不续存进度
            BlockHitResult hit = pickAlongView();
            if (hit != null) {
                digger.digStep(hit);
                digTicked = true;
            } else if (digger.current() != null) {
                digger.cancel();
            }
        } else {
            if (digger.current() != null) {
                digger.cancel();
            }
        }
        if (!digTicked) {
            digger.tickCooldown(); // 破块后冷却按游戏刻走,不随落空/松键冻结
        }

        if (t != null && t.hasRotation()) {
            AimProcessor.Rotation stepped = aim.step(startYaw, startPitch, t.getYaw(), t.getPitch());
            applyLook(stepped);
        }

        float forward = (isKeyRequested(Input.MOVE_FORWARD) ? 1.0f : 0.0f)
                + (isKeyRequested(Input.MOVE_BACK) ? -1.0f : 0.0f);
        float strafe = (isKeyRequested(Input.MOVE_LEFT) ? 1.0f : 0.0f)
                + (isKeyRequested(Input.MOVE_RIGHT) ? -1.0f : 0.0f);
        boolean sneak = isKeyRequested(Input.SNEAK);
        if (sneak) {
            // 潜行冲量乘数读属性(基值 0.3,迅捷潜行附魔会提高)
            float sneakSpeed = (float) player.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
            forward *= sneakSpeed;
            strafe *= sneakSpeed;
        }
        // 移动方向按实体当前视角(已步进):vanilla travel 按 player.yaw
        // 投影 zza/xxa,输入也必须按同一 yaw,否则 target 与 actual 的角差
        // 经 remap 被当成转向补偿加进 xxa/zza,长距离累积成横向漂移。
        // 视角步进到位前 forward 就只按当前朝向走,不预补偿。
        float moveYaw = player.getYRot();
        float[] impulse = AimProcessor.remapInput(strafe, forward, moveYaw, moveYaw);
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
     * 一次右键尝试:沿实际视角原生 raycast,未命中方块(或正在划桨)
     * 则本 tick 不算尝试(不扣冷却)。命中后扣冷却并按主手优先逐手试:
     * 先 useItemOn(对着方块放置/开门/交互),不消费再退回 useItem
     * (不针对方块的使用——倒水桶/收水这类只覆写 use() 的物品靠这条
     * 才能出手);任一消费即结束(useItemOn 消费才挥手)。
     */
    private void rightClickTick() {
        if (player.getControlledVehicle() instanceof net.minecraft.world.entity.vehicle.boat.Boat
                && (isKeyRequested(Input.MOVE_FORWARD) || isKeyRequested(Input.MOVE_BACK)
                        || isKeyRequested(Input.MOVE_LEFT) || isKeyRequested(Input.MOVE_RIGHT))) {
            return; // 驾船且本 tick 有移动输入(手在桨上)不右键;副座/静坐可点击
        }
        Level level = player.level();
        BlockHitResult hit = pickAlongView();
        if (hit == null) {
            return; // 未命中方块或被实体遮挡:不对空挥右键,也不扣冷却
        }
        rightClickCooldown = NavSettings.get().rightClickSpeed - 1;
        for (InteractionHand hand : HANDS) {
            ItemStack stack = player.getItemInHand(hand);
            if (player.gameMode.useItemOn(player, level, stack, hand, hit).consumesAction()) {
                player.swing(hand);
                return;
            }
            if (!stack.isEmpty()
                    && player.gameMode.useItem(player, level, stack, hand).consumesAction()) {
                return;
            }
        }
    }

    // ==================== 视线判定 ====================

    /** 实际视角的射线是否命中该格(实体遮挡视为未命中)。 */
    public boolean isLookingAt(BlockPos pos) {
        BlockHitResult hit = pickAlongView();
        return hit != null && hit.getBlockPos().equals(pos);
    }

    /**
     * 视线拾取:方块射线命中、且眼与命中点之间没有可拾取实体遮挡时
     * 返回命中;否则 null(实体挡视线的 tick 点击不落地)。
     */
    private BlockHitResult pickAlongView() {
        BlockHitResult hit = clipAlongView();
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = hit.getLocation();
        var entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                player, eye, end, new net.minecraft.world.phys.AABB(eye, end).inflate(1.0),
                e -> !e.isSpectator() && e.isPickable(), end.distanceToSqr(eye));
        return entityHit == null ? hit : null;
    }

    /** 实际视角是否已贴住目标转角(角度容差 0.01°;量化残差下很少成立,主判据是射线)。 */
    public boolean isFacingTarget(MovementState.MovementTarget target) {
        if (!target.hasRotation()) {
            return false;
        }
        return Math.abs(AimProcessor.normalizeDelta(player.getYRot() - target.getYaw())) < 0.01
                && Math.abs(player.getXRot() - target.getPitch()) < 0.01;
    }

    /** 准星此刻命中的方块;未命中或被实体遮挡返回 null。 */
    public BlockPos crosshairBlock() {
        BlockHitResult hit = pickAlongView();
        return hit != null ? hit.getBlockPos() : null;
    }

    /** 沿实体当前视角的轮廓射线(不穿流体);触及距离创造 5.0/生存按设置。 */
    private BlockHitResult clipAlongView() {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f)
                .scale(MovementHelper.blockReachDistance(player)));
        return player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /**
     * 该格上眼睛能实际射到的第一个瞄点(形状中心优先,再六面心);
     * 全部被挡返回 null。
     */
    private Vec3 reachableAimPoint(BlockPos pos) {
        return MovementHelper.reachableAimPoint(player, pos);
    }

    // ==================== 换手 / 备货 ====================

    /**
     * 保证快捷栏里有可垫路耗材:快捷栏已有则不动;背包深处有且
     * {@code allowInventory} 开启才换进当前选中格。
     */
    public boolean ensureThrowawayInHotbar() {
        var acceptable = NavSettings.get().acceptableThrowawayItems();
        return ensureInHotbar(stack -> !stack.isEmpty() && acceptable.contains(stack.getItem()));
    }

    /** 保证快捷栏里有水桶(坠落接水前备货;背包深处仅 allowInventory 时动用)。 */
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
        if (!NavSettings.get().allowInventory) {
            return false;
        }
        for (int i = 9; i < inv.getNonEquipmentItems().size(); i++) {
            if (what.test(inv.getItem(i))) {
                player.holdInHand(i);
                return true;
            }
        }
        return false;
    }
}
