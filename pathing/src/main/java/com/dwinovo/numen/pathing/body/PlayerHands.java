package com.dwinovo.numen.pathing.body;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.DigTime;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的两只手,原版机制只此一份:左键挖照原版客户端的挖掘循环({@code MultiPlayerGameMode} 的
 * {@code startDestroyBlock}、{@code continueDestroyBlock}、{@code stopDestroyBlock}),右键照原版客户端的
 * {@code Minecraft.startUseItem}。送到服务端的,是真客户端的包到了服务端之后走的同一个入口——挖掘经
 * {@code handlePlayerAction},右键在 {@code handleUseItemOn} 的同一套检查之后调 {@code useItemOn}、{@code useItem}。
 * 服务端自己的规矩(够不够得着、出生点保护、冒险模式、别的模组取消了事件)都在那些入口里生效。
 *
 * <h2>挖</h2>
 * 第一下送 START:秒破的方块与创造模式当场碎;其余每刻累加原版的 {@code getDestroyProgress},到 1 送 STOP,服务端按它
 * 自己的钟确认后挖掉。碎掉之后手要缓几刻才挖下一格,照原版客户端的 {@code destroyDelay}({@link DigTime#cooldown}:
 * 累着进度挖碎的与创造模式缓 5 刻,秒破的不缓),规划给挖一格定价按的是同一个。手上的东西换了就从头挖;
 * 原地变了的(修补附魔吸经验改了耐久)还是同一件,不重开——原版客户端记的是手上那一叠本身。
 *
 * <p>服务端没挖掉就是没让挖:送 STOP 之后方块没变,再等服务端自己的钟走一阵(它记的进度偶尔比客户端慢,会挂成延迟
 * 破坏,过几刻自己挖掉);还不变就以 {@link Refusal#SERVER} 收场,不空挥到超时。
 *
 * <h2>右键</h2>
 * 两次右键至少隔 4 刻;正在挖时不按;主手先试,方块上用不了、手里又不空就对空用,再不成换副手。按下去之后,点中的那一格
 * 与它面前那一格(各连同上下一格,门的另一半在那里)哪几格变了,就是这一下的结果。
 *
 * <p>缓手按游戏刻计(世界的游戏时间),不随有没有按键、有没有被调用而冻住;调高刻速率也一样。
 */
public final class PlayerHands implements Effector {

    /** 模块自己看出来的拒绝理由。宿主的拒绝用宿主自己的理由。 */
    public enum Refusal {
        /** 服务端没让这一下落地:挖了没碎。 */
        SERVER
    }

    /** 原版客户端两次右键之间的刻数。 */
    private static final int RIGHT_CLICK_DELAY = 4;
    /** 送了 STOP 之后,最多等服务端这么多刻把延迟破坏落地。 */
    private static final int STOP_GRACE = 20;

    private final ServerPlayer body;
    private int sequence;
    private long destroyReadyAt;
    private long useReadyAt;
    /** 正在挖的那一格;没在挖为 null。 */
    private BlockPos destroying;
    /** 开挖时手上那一叠本身(不是副本)。 */
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float progress;
    /** 送过 STOP、等服务端落地的那一格与原来的方块;没在等为 null。 */
    private BlockPos stopped;
    private BlockState stoppedState;
    private long stopDeadline;

    public PlayerHands(ServerPlayer body) {
        this.body = body;
    }

    /** 左键正按在某一格上(包括送了 STOP 在等服务端落地)。 */
    public boolean digging() {
        return pressing() != null;
    }

    /** 左键正按在哪一格上(包括送了 STOP 在等服务端落地的那一格);没按为 null。 */
    public BlockPos pressing() {
        return stopped != null ? stopped : destroying;
    }

    private long now() {
        return body.level().getGameTime();
    }

    // ==================== 左键 ====================

    @Override
    public Strike dig(BlockHitResult hit) {
        ServerLevel level = body.serverLevel();
        if (stopped != null) {
            return awaitStop(level);
        }
        if (now() < destroyReadyAt) {
            return Strike.SWINGING;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        Direction face = hit.getDirection();
        BlockState state = level.getBlockState(pos);
        if (body.gameMode.isCreative()) {
            cooldown(true, true);
            body.swing(InteractionHand.MAIN_HAND);
            send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
            return landed(level, pos, state);
        }
        if (destroying == null || !destroying.equals(pos)
                || !ItemStack.isSameItemSameComponents(body.getMainHandItem(), destroyingItem)) {
            return start(level, pos, face, state);
        }
        if (state.isAir()) {
            destroying = null;
            return Strike.SWINGING;
        }
        progress += state.getDestroyProgress(body, level, pos);
        body.swing(InteractionHand.MAIN_HAND);
        if (progress < 1.0F) {
            return Strike.SWINGING;
        }
        destroying = null;
        progress = 0;
        cooldown(false, false);
        send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face);
        if (level.getBlockState(pos) != state) {
            return new Strike.Broke(pos, state);
        }
        stopped = pos;
        stoppedState = state;
        stopDeadline = now() + STOP_GRACE;
        return Strike.SWINGING;
    }

    /** 碎掉一格之后缓手:缓的那几刻里不挖,下一刻才挖下一格。 */
    private void cooldown(boolean creative, boolean instant) {
        destroyReadyAt = now() + DigTime.cooldown(creative, instant) + 1;
    }

    /** 第一下:原版客户端换了目标就先放下旧的,再送 START;秒破的当场碎,否则开始累加进度。 */
    private Strike start(ServerLevel level, BlockPos pos, Direction face, BlockState state) {
        release();
        body.swing(InteractionHand.MAIN_HAND);
        boolean instant = !state.isAir() && state.getDestroyProgress(body, level, pos) >= 1.0F;
        send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
        if (instant) {
            return landed(level, pos, state);
        }
        destroying = pos;
        destroyingItem = body.getMainHandItem();
        progress = 0;
        return Strike.SWINGING;
    }

    /** 当场该碎的那一下落没落地。 */
    private static Strike landed(ServerLevel level, BlockPos pos, BlockState before) {
        return level.getBlockState(pos) != before
                ? new Strike.Broke(pos, before)
                : new Strike.Refused(pos, Refusal.SERVER);
    }

    /** 送过 STOP 还没碎:服务端挂了延迟破坏会在它自己的刻里挖掉,等一阵;一直不变就是没让挖。 */
    private Strike awaitStop(ServerLevel level) {
        BlockPos pos = stopped;
        if (level.getBlockState(pos) != stoppedState) {
            stopped = null;
            return new Strike.Broke(pos, stoppedState);
        }
        if (now() < stopDeadline) {
            return Strike.SWINGING;
        }
        stopped = null;
        return new Strike.Refused(pos, Refusal.SERVER);
    }

    @Override
    public void release() {
        if (destroying != null) {
            send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroying, Direction.DOWN);
            body.resetAttackStrengthTicker();
        }
        destroying = null;
        progress = 0;
    }

    /** 这一个动作经服务端收包的入口送进去。 */
    private void send(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        body.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, pos, face, ++sequence));
    }

    // ==================== 右键 ====================

    @Override
    public Use use(BlockHitResult hit) {
        if (now() < useReadyAt || digging()) {
            return Use.WAITING;
        }
        useReadyAt = now() + RIGHT_CLICK_DELAY;
        ServerLevel level = body.serverLevel();
        Map<BlockPos, BlockState> before = around(level, hit);
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = body.getItemInHand(hand);
            if (!stack.isItemEnabled(level.enabledFeatures())) {
                break;
            }
            InteractionResult onBlock = useItemOn(level, stack, hand, hit);
            if (onBlock.consumesAction() || onBlock == InteractionResult.FAIL) {
                break;
            }
            if (!stack.isEmpty() && body.gameMode.useItem(body, level, stack, hand).consumesAction()) {
                break;
            }
        }
        List<Change> changes = new ArrayList<>();
        before.forEach((pos, was) -> {
            BlockState now = level.getBlockState(pos);
            if (now != was) {
                changes.add(new Change(pos, was, now));
            }
        });
        return changes.isEmpty() ? Use.NOTHING : new Use.Changed(changes);
    }

    /**
     * 原版 {@code handleUseItemOn} 在调 {@code useItemOn} 之前的检查:够得着(交互距离加 1 格宽限)、点中的位置落在那一格上、
     * 不在建筑高度之上、世界许这具身体动它。
     */
    private InteractionResult useItemOn(ServerLevel level, ItemStack stack, InteractionHand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Vec3 offset = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        if (!body.canInteractWithBlock(pos, 1.0) || Math.abs(offset.x) >= 1.0000001
                || Math.abs(offset.y) >= 1.0000001 || Math.abs(offset.z) >= 1.0000001) {
            return InteractionResult.PASS;
        }
        body.resetLastActionTime();
        if (pos.getY() >= level.getMaxBuildHeight() || !level.mayInteract(body, pos)) {
            return InteractionResult.PASS;
        }
        InteractionResult result = body.gameMode.useItemOn(body, level, stack, hand, hit);
        if (result.consumesAction()) {
            CriteriaTriggers.ANY_BLOCK_USE.trigger(body, pos, stack.copy());
        }
        if (result.shouldSwing()) {
            body.swing(hand, true);
        }
        return result;
    }

    /** 右键可能改到的几格:点中的那一格、它面前那一格,各连同上下一格。 */
    private static Map<BlockPos, BlockState> around(ServerLevel level, BlockHitResult hit) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (BlockPos center : new BlockPos[] {hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection())}) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = center.offset(0, dy, 0).immutable();
                out.putIfAbsent(pos, level.getBlockState(pos));
            }
        }
        return out;
    }
}
