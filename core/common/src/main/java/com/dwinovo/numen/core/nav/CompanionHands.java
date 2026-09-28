package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.body.PlayerHands;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.plan.ToolChoice;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 端口 {@link Effector} 在同伴身上的实现:原版的两只手({@link PlayerHands})外面套一层权限——每一下之前把这个动作交给权限层
 * 裁决,不许就不动手,交回裁决本身当理由({@link Verdict})。一具身体一份,导航、挖矿、施工用的是同一双手。
 *
 * <ul>
 *   <li>左键:换了一格挖,先问能不能拆这一格;同一格接着挖不再问。</li>
 *   <li>右键:手上的东西点下去会往世界里放东西(方块、桶、打火石),问的是放下它;否则问的是用这一格(开门、开箱)。
 *       主手先试,不成再用副手,和原版按键同一个顺序,两只手各问各的。</li>
 * </ul>
 * 服务端自己退回的挖掘由 {@link PlayerHands} 如实报成被拒,那是身体汇报,不是权限裁决。
 */
public final class CompanionHands implements Effector {

    private final NumenPlayer player;
    private final PlayerHands hands;

    private CompanionHands(NumenPlayer player) {
        this.player = player;
        this.hands = new PlayerHands(player);
    }

    /** 这具身体的手(首次取时建)。 */
    public static CompanionHands of(NumenPlayer player) {
        return player.state(CompanionHands.class, () -> new CompanionHands(player));
    }

    /**
     * 把挖 {@code state} 最快的那件拿到手上——与寻路给挖掘定价、执行路上挖时拿工具是同一份挑法({@link ToolChoice},看全背包,
     * 一样快时空手优先、其次不耗耐久的)。换了就交回做了什么。
     */
    public Optional<BodyAction> takeToolFor(BlockState state) {
        return Hotbar.hold(player, new ToolChoice(Snapshots.of(player)).best(state).slot());
    }

    /** 左键正按在哪一格上;没按为 null。 */
    public BlockPos pressing() {
        return hands.pressing();
    }

    @Override
    public Strike dig(BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (!pos.equals(hands.pressing())) {
            Verdict verdict = Permission.judge(player, Action.breakBlock(pos, player.level().getBlockState(pos)));
            if (!verdict.allowed()) {
                hands.release();
                return new Strike.Refused(pos.immutable(), verdict);
            }
        }
        return hands.dig(hit);
    }

    @Override
    public void release() {
        hands.release();
    }

    @Override
    public Use use(BlockHitResult hit) {
        for (InteractionHand hand : InteractionHand.values()) {
            Action action = Interaction.placementOf(player.level(), hit, player.getItemInHand(hand));
            if (action == null) {
                continue;
            }
            Verdict verdict = Permission.judge(player, action);
            if (!verdict.allowed()) {
                return new Use.Refused(action.pos(), verdict);
            }
        }
        BlockPos clicked = hit.getBlockPos();
        Verdict verdict = Permission.judge(player, Action.useBlock(clicked, player.level().getBlockState(clicked)));
        if (!verdict.allowed()) {
            return new Use.Refused(clicked.immutable(), verdict);
        }
        return hands.use(hit);
    }
}
