package com.dwinovo.numen.core.act;

import java.util.function.Consumer;
import java.util.function.Predicate;

import com.dwinovo.numen.core.nav.CompanionHands;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Effector.Strike;
import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.world.Sight;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 挖掘执行({@code work dig},建造清场也交给它)挖一格时怎么挖:转过去看着它、把挖它最快的那件拿到手上、按住左键直到它碎。
 * 破坏方块只有一条路,就是她身上那双手({@link CompanionHands}:原版的挖掘循环外面套着权限层)——导航路上挖、挖掘执行、
 * {@code use block} 左键用的是同一双手,所以每一格动手之前都过权限层,被拒的格一下都不挥,报 {@link DigResult#REFUSED};
 * 服务端退回的(别的模组取消了破坏、出生点保护、冒险模式)同样报被拒,理由是 {@link #SERVER_REFUSED}——不空挥到超时,也不把
 * 没挖掉的方块报成挖掉了。创造模式一下就碎、生存模式按工具算时间,是原版的手自己分的。
 *
 * <p>看不见目标(树叶挡着、头顶太窄)时,在够得着的瞄点里朝隔着的格都清得掉、挡得最少的那一点看过去({@link Sight#dig}——
 * 与导航给挖一格的站位定价是同一个判据),准星落在的那一格就是挡着的:先挖它把视线打开,而不是站着等一个永远不来的角度。清不清得掉由调用方
 * 给(挖的一方的判据:规格、许可、会不会出事),这里不另判。
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

    /** 挖一刻的结果。 */
    public enum DigResult {
        /** 还在挖,或上一格刚碎、手还没缓过来——接着调。 */
        PROGRESSING,
        /** 目标这一刻碎了。 */
        BROKE_TARGET,
        /** 挡在前面的那一格这一刻碎了(不是目标)——离目标近了一步。 */
        BROKE_OCCLUDER,
        /** 目标没有一个面看得见、隔着的格也清不掉——卡住了(对应 OCCLUDED)。 */
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

    /**
     * 挖 {@code target} 一刻:看着它(看不见就朝隔着的格都 {@code mayClear} 的那一点看,准星落在挡着的那一格上),换了一格挖
     * 就先把挖它最快的那件拿到手上,按住左键。{@code mayClear} 是挖的一方清得掉哪些遮挡的判据,与它交给导航的挖一格目标
     * ({@code Goals.dig})是同一个。
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
            Sight.Trace line = Sight.dig(player.level(), player.getEyePosition(), target,
                    Aim.digPoints(player, target), mayClear);
            if (line == null) {
                return DigResult.NO_SHOT;
            }
            Aim.look(player, line.point());
            BlockPos blocker = Crosshair.pick(player) instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
            if (blocker == null || blocker.equals(target) || !mayClear.test(blocker)) {
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
                Verdict verdict = com.dwinovo.numen.core.nav.CompanionHands.verdict(refused.reason());
                refusal = verdict != null ? verdict : Verdict.deny(SERVER_REFUSED);
                yield DigResult.REFUSED;
            }
        };
    }

    /** 松开左键:正在挖的那一格放下,进度清零。 */
    public void cancel() {
        CompanionHands.of(player).release();
    }
}
