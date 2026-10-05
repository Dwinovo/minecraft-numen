package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.LiveWorld;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.DigRules;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Sight;
import com.dwinovo.numen.permission.Listing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 挡着视线的格清不清得掉:按这份规格挖它能不能进路线({@link CostModel#admitDig})——规格的禁令、物理上挖不挖得了、许可
     * 怎么答(要问主人的只在规格把它们算能走时进,不许的永远不进)。交给挖一格的目标给站位定价({@code Goals.dig}),也交给
     * 挖掘器清遮挡:两处问的是这同一个。只读冻结的成本模型,可以在搜索线程上问。
     */
    public Goals.Clearing clearing() {
        CostModel frozen = model;
        return (view, pos) -> frozen.admitDig(view, pos, view.getBlockState(pos)).ok();
    }

    /** 在活世界上问 {@link #clearing}:挡着视线的这一格清得掉。 */
    public boolean clears(BlockPos pos) {
        return clearing().clears(world, pos);
    }

    /** 按这份规格挖这一格为什么不行(回执里的说法):挡着视线的清不清得掉、点名的目标挖不挖得成都问它;挖得了为 null。 */
    public String uncleared(BlockPos pos) {
        CostModel.Admission admission = model.admitDig(world, pos, world.getBlockState(pos));
        return admission.ok() ? null : NavText.refused(admission.refused(), admission.detail());
    }

    /**
     * {@code ore} 的每一面都贴着一整块按这份规格清不掉的方块时,说它被哪几格、为什么围住(那一格方块、坐标、许可或规格给的理由);
     * 有一面露着或贴着清得掉的为 null。这样的一格从哪个站位都看不见:挖的一方说够不着它时就说这一句。
     */
    public String walledIn(BlockPos ore) {
        Map<String, List<String>> cellsByWhy = new LinkedHashMap<>();
        for (Direction side : Direction.values()) {
            BlockPos front = ore.relative(side);
            String why = Sight.open(world, ore, side) ? null : uncleared(front);
            if (why == null) {
                return null;
            }
            cellsByWhy.computeIfAbsent(why, k -> new ArrayList<>())
                    .add(NavText.name(world.getBlockState(front)) + " at " + Listing.coords(front));
        }
        List<String> parts = new ArrayList<>(cellsByWhy.size());
        cellsByWhy.forEach((why, cells) -> parts.add(String.join("; ", cells) + " (" + why + ")"));
        return "every face of " + NavText.name(world.getBlockState(ore)) + " at " + Listing.coords(ore)
                + " is covered by a block I may not break: " + String.join(", ", parts)
                + "; no stance lets me see it, and those blocks are not mine to get around, so dig something else or"
                + " ask your owner";
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
