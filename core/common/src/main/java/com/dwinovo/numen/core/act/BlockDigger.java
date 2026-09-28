package com.dwinovo.numen.core.act;

import java.util.function.Consumer;
import java.util.function.Predicate;

import com.dwinovo.numen.core.nav.CompanionHands;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Effector.Strike;
import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 任务自己要挖一格时怎么挖:转过去看着它、把挖它最快的那件拿到手上、按住左键直到它碎。挖法只有一份,就是她身上那双手
 * ({@link CompanionHands}:原版的挖掘循环外面套着权限层)——导航路上挖、挖矿、{@code use block} 左键用的是同一双手,所以
 * 每一格动手之前都过权限层,被拒的格一下都不挥,报 {@link DigResult#REFUSED};服务端退回的(别的模组取消了破坏、出生点保护、
 * 冒险模式)同样报被拒,理由是 {@link #SERVER_REFUSED}——不空挥到超时,也不把没挖掉的方块报成挖掉了。
 *
 * <p>看不见目标(树叶挡着、头顶太窄)时,朝它身上够得着的那一点看过去({@link Aim#reachable}),准星落在的那一格就是挡着的:
 * 调用方说挖它不会出事、权限层也许,就先挖它把视线打开,而不是站着等一个永远不来的角度。
 *
 * <p>施工清障与收工撤垫块另走一次到位的原生破坏({@link #destroyNow}),同样先过权限层。
 */
public final class BlockDigger {

    /** 服务端把挖掘退回来时回执里的理由。 */
    public static final String SERVER_REFUSED = "服务器没让挖掉这一格";

    private final NumenPlayer player;
    /** 最近一次 {@link DigResult#REFUSED} 的理由(权限层的裁决,或服务端退回的 {@link #SERVER_REFUSED});没被拒过是 null。 */
    private Verdict refusal;

    public BlockDigger(NumenPlayer player) {
        this.player = player;
    }

    /** 正在挖的那一格;没在挖为 null。 */
    public BlockPos current() {
        return CompanionHands.of(player).pressing();
    }

    /**
     * 最近一次真挖掉的那一格和它挖掉前的方块。{@link DigResult#BROKE_OCCLUDER} 挖掉的是挡在前面的那格,
     * 不是调用方给的目标,记账要记这里说的这一格。
     */
    public record Broken(BlockPos pos, BlockState was) {}

    private Broken lastBroken;

    /** 最近一次 {@link DigResult#broke()} 挖掉的是哪一格;还没挖掉过是 null。 */
    public Broken lastBroken() {
        return lastBroken;
    }

    /** Why the last {@link DigResult#REFUSED} happened; {@code null} if nothing was refused yet. */
    public Verdict refusal() {
        return refusal;
    }

    /**
     * 施工清障与收工撤垫块:一次到位的原生破坏({@code ServerPlayerGameMode.destroyBlock}——掉落按手持结算、
     * 创造不掉、别的模组的破坏事件照常触发),不走逐刻进度,也不要求视线。同样先过权限层。
     *
     * @return 方块真的没了;没碎而那一格不是空气时,{@link #refusal()} 说为什么
     */
    public boolean destroyNow(BlockPos target) {
        BlockState state = player.level().getBlockState(target);
        if (state.isAir()) {
            return false;
        }
        Verdict verdict = Permission.judge(player, Action.breakBlock(target, state));
        if (!verdict.allowed()) {
            refusal = verdict;
            return false;
        }
        if (!player.gameMode.destroyBlock(target)) {
            // 权限层放行了,原生通道却没让它碎(别的模组取消了破坏事件、出生点保护):和逐刻挖掘被退回同一个说法
            refusal = Verdict.deny(SERVER_REFUSED);
            return false;
        }
        return true;
    }

    /** 挖一刻的结果。 */
    public enum DigResult {
        /** 还在挖,或上一格刚碎、手还没缓过来——接着调。 */
        PROGRESSING,
        /** 目标这一刻碎了。 */
        BROKE_TARGET,
        /** 挡在前面的那一格这一刻碎了(不是目标)——离目标近了一步。 */
        BROKE_OCCLUDER,
        /** 目标没有一个面看得见、挡着的也挖不得——卡住了(对应 OCCLUDED)。 */
        NO_SHOT,
        /**
         * 权限层在第一下之前没让挖,或服务端把这一下退回来了(别的模组取消了、原版的出生点保护);{@link #refusal()} 说为什么。
         * 那一格没被碰过。
         */
        REFUSED;

        /** 这一刻有方块真的没了(目标或遮挡物)。 */
        public boolean broke() {
            return this == BROKE_TARGET || this == BROKE_OCCLUDER;
        }
    }

    /** 挖 {@code target} 一刻,挡在前面的哪一格都可以先挖开。 */
    public DigResult digStep(BlockPos target, Consumer<BodyAction> told) {
        return digStep(target, occluder -> true, told);
    }

    /**
     * 挖 {@code target} 一刻:看着它(看不见就看它的中心,准星落在挡着的那一格上),换了一格挖就先把挖它最快的那件拿到手上,
     * 按住左键。挡在前面的那一格还要 {@code mayClear} 点头才挖:权限层管许不许,调用方管这一格挖了会不会出事(比如挖矿按
     * 自己挑目标的那道剪枝,不挖贴着流体、顶着落沙的)。
     *
     * @param told 身体为这一下做的动作(把工具拿到手上)交给它,由任务记进回执
     */
    public DigResult digStep(BlockPos target, Predicate<BlockPos> mayClear, Consumer<BodyAction> told) {
        player.controls().stop();
        BlockPos effective = target;
        Vec3 point = Aim.point(player, target);
        if (point != null) {
            Aim.look(player, point);
        } else {
            Vec3 toward = Aim.reachable(player, target);
            if (toward == null) {
                return DigResult.NO_SHOT;
            }
            Aim.look(player, toward);
            BlockPos blocker = Crosshair.pick(player) instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
            if (blocker == null || blocker.equals(target) || !mayClear.test(blocker)
                    || !Permission.judge(player, Action.breakBlock(blocker, player.level().getBlockState(blocker)))
                            .allowed()) {
                return DigResult.NO_SHOT;
            }
            effective = blocker;
        }
        BlockHitResult hit = Crosshair.on(player, effective);
        if (hit == null) {
            return DigResult.NO_SHOT;
        }
        CompanionHands hands = CompanionHands.of(player);
        if (!effective.equals(hands.pressing())) {
            // 换了一格挖:先把挖它最快的那件拿到手上(与寻路给挖掘定价用的是同一份挑法)
            hands.takeToolFor(player.level().getBlockState(effective)).ifPresent(told);
        }
        Strike strike = hands.dig(hit);
        return switch (strike) {
            case Strike.Swinging swinging -> DigResult.PROGRESSING;
            case Strike.Broke broke -> {
                lastBroken = new Broken(broke.pos(), broke.before());
                yield broke.pos().equals(target) ? DigResult.BROKE_TARGET : DigResult.BROKE_OCCLUDER;
            }
            case Strike.Refused refused -> {
                refusal = refused.reason() instanceof Verdict verdict ? verdict : Verdict.deny(SERVER_REFUSED);
                yield DigResult.REFUSED;
            }
        };
    }

    /** 松开左键:正在挖的那一格放下,进度清零。 */
    public void cancel() {
        CompanionHands.of(player).release();
    }
}
