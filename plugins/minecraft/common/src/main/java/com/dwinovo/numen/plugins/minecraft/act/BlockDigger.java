package com.dwinovo.numen.plugins.minecraft.act;

import java.util.function.Consumer;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.api.entity.Mouse.Strike;
import com.dwinovo.numen.plugins.minecraft.pathing.plan.ToolChoice;
import com.dwinovo.numen.plugins.minecraft.pathing.api.Snapshots;
import com.dwinovo.numen.api.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 挖掘执行({@code work dig})挖一格时怎么挖:朝它转一下、把挖它最快的那件拿到手上、按住左键直到它碎。
 * 破坏方块只有一条路,就是她的鼠标({@link NumenPlayer#mouse}:原版的挖掘循环外面套着权限层)——导航路上挖、挖掘执行、
 * {@code use block} 左键用的是同一双手,所以每一格动手之前都过权限层,被拒的格一下都不挥,报 {@link DigResult#REFUSED};
 * 服务端退回的(别的模组取消了破坏、出生点保护、冒险模式)同样报被拒,理由是 {@link #SERVER_REFUSED}——不空挥到超时,也不把
 * 没挖掉的方块报成挖掉了。创造模式一下就碎、生存模式按工具算时间,是原版的手自己分的。
 *
 * <p>指名哪一格就挖哪一格:够得着就挖得动,中间隔着什么不问。
 */
public final class BlockDigger {

    /** 服务端把挖掘退回来时回执里的理由。 */
    public static final String SERVER_REFUSED = "the server did not let this block be broken";

    private final NumenPlayer player;
    /** 最近一次 {@link DigResult#REFUSED} 的理由(权限层的裁决,或服务端退回的 {@link #SERVER_REFUSED});没被拒过是 null。 */
    private Verdict refusal;

    public BlockDigger(NumenPlayer player) {
        this.player = player;
    }

    /** Why the last {@link DigResult#REFUSED} happened; {@code null} if nothing was refused yet. */
    public Verdict refusal() {
        return refusal;
    }

    /** 挖一刻的结果。 */
    public enum DigResult {
        /** 还在挖——接着调。 */
        PROGRESSING,
        /** 这一格这一刻碎了。 */
        BROKE,
        /** 这一格够不着(身体挪开了):这一下没按。 */
        OUT_OF_REACH,
        /**
         * 权限层在第一下之前没让挖,或服务端把这一下退回来了(别的模组取消了、原版的出生点保护);{@link #refusal()} 说为什么。
         * 那一格没被碰过。
         */
        REFUSED
    }

    /**
     * 挖 {@code target} 一刻:朝它转一下,换了一格挖就先把挖它最快的那件拿到手上(与寻路给挖掘定价用的是同一份挑法),按住左键。
     *
     * @param told 身体为这一下做的动作(把工具拿到手上)交给它,由任务记进回执
     */
    public DigResult digStep(BlockPos target, Consumer<BodyAction> told) {
        player.controls().stop();
        player.look().at(Vec3.atCenterOf(target));
        if (!target.equals(player.mouse().pressing())) {
            new ToolChoice(Snapshots.of(player)).take(player.level().getBlockState(target), player.hotbar()).ifPresent(told);
        }
        return switch (player.mouse().dig(target)) {
            case Strike.Swinging swinging -> DigResult.PROGRESSING;
            case Strike.Missed missed -> DigResult.OUT_OF_REACH;
            case Strike.Broke broke -> DigResult.BROKE;
            case Strike.Refused refused -> {
                Verdict verdict = refused.reason().verdict();
                refusal = verdict != null ? verdict : Verdict.deny(SERVER_REFUSED);
                yield DigResult.REFUSED;
            }
        };
    }

    /** 正在挖的那一格送过一次 STOP、方块还在({@link com.dwinovo.numen.api.entity.Mouse#stopSent})。 */
    public boolean stopSent() {
        return player.mouse().stopSent();
    }

    /** 松开左键:正在挖的那一格放下,进度清零。 */
    public void cancel() {
        player.mouse().release();
    }
}
