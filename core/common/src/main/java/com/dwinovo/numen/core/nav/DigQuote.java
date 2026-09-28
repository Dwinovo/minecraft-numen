package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.DigRules;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 她挖一格:挖不挖得成、要付多少——与寻路给路上一格定价是同一个成本模型({@link CompanionPorts},此刻的身体、权限快照、
 * 垫路料、附近的生物),同一套挖掘规则。挑目标的活(挖矿)按它给每一格定价,不另算一份挖多久、许不许。
 *
 * <p>在世界所在的线程上取,读活世界;身体与权限变了就再取一份。
 */
public final class DigQuote {

    private final CostModel model;
    private final LiveWorld world;

    private DigQuote(CostModel model, LiveWorld world) {
        this.model = model;
        this.world = world;
    }

    /** 按 {@code spec} 与她此刻的身体、端口给挖掘定价。 */
    public static DigQuote of(NumenPlayer player, RouteSpec spec) {
        return new DigQuote(CompanionPorts.model(player, spec), new LiveWorld(player.serverLevel()));
    }

    /**
     * 挖这一格的价钱({@code refusal} 为 null),或挖不成(价钱无穷,{@code refusal} 是许可给的理由;规格禁的、物理上挖不了的
     * 为 null)。价钱按站着挖、眼睛不在水里算。
     */
    public Price price(BlockPos pos, BlockState state) {
        CostModel.Admission admission = model.admitDig(world, pos, state);
        if (!admission.ok()) {
            return new Price(Double.POSITIVE_INFINITY, admission.refused() == Reason.DENIED ? admission.detail() : null);
        }
        return new Price(model.digCost(new Edit.Dig(pos, state, admission.permit(), false, true)), null);
    }

    /**
     * 这一格挖不挖得成:规格的按位置与按种类禁令、物理上挖不挖得了(挖不动、贴着流体、顶着落沙、世界边界外)。许可不许的
     * 也算挖得成——那是动手时权限层的事,价钱是无穷。
     */
    public boolean breakable(BlockPos pos, BlockState state) {
        CostModel.Admission admission = model.admitDig(world, pos, state);
        return admission.ok() || admission.refused() == Reason.DENIED;
    }

    /**
     * 这一格物理上处置得了:挖得动、不贴着流体、不顶着落沙、在世界边界里——只问挖掘规则,不问规格与许可,与她怎么走过去
     * 无关。
     */
    public static boolean physicallyDiggable(ServerPlayer player, BlockPos pos) {
        LiveWorld world = new LiveWorld(player.serverLevel());
        return DigRules.check(world, Snapshots.of(player), pos, world.getBlockState(pos), false) == null;
    }

    /**
     * 挖一格的报价。
     *
     * @param cost    价钱;挖不成是无穷
     * @param refusal 挖不成是因为许可不许时,许可给的理由(权限层的裁决);否则为 null
     */
    public record Price(double cost, Object refusal) {}
}
