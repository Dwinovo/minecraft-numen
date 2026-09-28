package com.dwinovo.numen.pathing.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Bounds;
import com.dwinovo.numen.pathing.world.Faces;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Replaceable;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 成本模型:一次搜索(或一次执行复核)"能做什么、每样多贵"的全部依据。它由几部分组合而成,每部分一个出处:
 * <ul>
 *   <li>物理代价与罚分常量——{@link ActionCosts};</li>
 *   <li>路线规格——{@link RouteSpec}:能力开关、排除的格子种类、按位置与按种类、四项动作罚分;</li>
 *   <li>许可——端口 {@link TerrainPolicy}:一格能不能挖或放;</li>
 *   <li>垫路料——端口 {@link Materials}:建模型时问一次下一块用什么,没有就是没料;</li>
 *   <li>身体——{@link BodySnapshot}:迈步、起跳、交互距离、游戏模式、摔伤与摔落上限、背包里的工具({@link ToolChoice});</li>
 *   <li>生物危险——端口 {@link Threats}:建模型时问一次,变成按位置的代价。</li>
 * </ul>
 * 不许继承;任务要改价,只能换一份路线规格({@link #withSpec}),或在规格的按位置代价表里加减。
 *
 * <p>挖与放能不能进路线、放一块与挖一格多少钱,都只在这里定:{@link #admitDig}、{@link #admitPlace}、{@link #digCost}、
 * {@link #placeCost}。放一块的价钱在规格要求事后拆回时连拆的那一下一起算,也只在 {@link #placeCost} 里。
 */
public final class CostModel {

    private final RouteSpec spec;
    private final BodySnapshot body;
    private final TerrainPolicy terrain;
    /** 下一块垫路料;没料为 null。 */
    private final Block placing;
    private final PositionCosts danger;
    private final ToolChoice tools;
    /** 规格排除的格子种类,按 {@link Semantics#mask(java.util.Set)} 编成掩码。 */
    private final int excluded;

    private CostModel(RouteSpec spec, BodySnapshot body, TerrainPolicy terrain, Block placing, PositionCosts danger,
                      ToolChoice tools) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.body = Objects.requireNonNull(body, "body");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.placing = placing;
        this.danger = danger;
        this.tools = tools;
        this.excluded = Semantics.mask(spec.excluded());
    }

    /** 在派发那一刻组一份:问一次垫路料、问一次生物危险。 */
    public static CostModel of(RouteSpec spec, BodySnapshot body, TerrainPolicy terrain, Materials materials,
                               Threats threats) {
        Block next = materials.next().orElse(null);
        return new CostModel(spec, body, terrain, next, dangerCosts(threats.current()), new ToolChoice(body));
    }

    /** 同一具身体、同样的端口答复,换一份路线规格。 */
    public CostModel withSpec(RouteSpec spec) {
        return new CostModel(spec, body, terrain, placing, danger, tools);
    }

    /** 同一份模型,换一个许可(诊断"换一种答复有没有路"用)。 */
    public CostModel withTerrain(TerrainPolicy terrain) {
        return new CostModel(spec, body, terrain, placing, danger, tools);
    }

    /** 同一份模型,下一块垫路料换成 {@code placing}(诊断"有料的话有没有路"用)。 */
    public CostModel withPlacing(Block placing) {
        return new CostModel(spec, body, terrain, placing, danger, tools);
    }

    public TerrainPolicy terrain() {
        return terrain;
    }

    public RouteSpec spec() {
        return spec;
    }

    public BodySnapshot body() {
        return body;
    }

    public ToolChoice tools() {
        return tools;
    }

    /** 下一块垫路料;身上没料为空。 */
    public Optional<Block> placing() {
        return Optional.ofNullable(placing);
    }

    // ==================== 身体与规格合起来的上限 ====================

    /**
     * 从 {@code drop} 高处站着落地、摔掉 {@code damage} 点血({@link BodySnapshot#fallDamage}),这一下摔不摔得起:
     * 身体受得起({@link BodySnapshot#bears}),落差也在规格的无水落差以内——规格只能收紧身体的上限。
     */
    public boolean bearsFall(double drop, int damage) {
        return body.bears(damage) && drop <= spec.maxFallHeightNoWater() + Footing.EPSILON;
    }

    /** 这次能不能疾跑:规格开着,身体也跑得动。 */
    public boolean maySprint() {
        return spec.sprint() && body.canSprint();
    }

    /** 水里走或游一格:按水下移动效率在水里的步速与陆上步速之间插值。 */
    public double waterStep() {
        double efficiency = Mth.clamp(body.waterMovementEfficiency(), 0, 1);
        return ActionCosts.WALK_ONE_IN_WATER * (1 - efficiency) + ActionCosts.WALK_ONE_BLOCK * efficiency;
    }

    // ==================== 按种类与按位置 ====================

    /** 种类掩码是 {@code kinds} 的格,这条路线排除它吗:任何一种被排除,整格就排除。 */
    public boolean excludes(int kinds) {
        return (kinds & excluded) != 0;
    }

    /** 这一格禁不禁止这样用:只有路线规格能禁。 */
    public boolean forbids(Use use, long cell) {
        return spec.positions().forbids(use, cell);
    }

    /** 这样用这一格额外加的价钱:规格的按位置代价加上生物危险。 */
    public double extra(Use use, long cell) {
        return spec.positions().extra(use, cell) + danger.extra(use, cell);
    }

    // ==================== 挖与放能不能进路线 ====================

    /**
     * 挖与放的准入:许可的答复,或拒绝的原因(连同许可给的理由)。
     *
     * @param permit  准入时许可的答复(放行或要问);拒绝时为 null
     * @param refused 拒绝的原因;准入时为 null
     * @param detail  许可拒绝时它给的理由
     */
    public record Admission(Permit permit, Reason refused, Object detail) {

        static Admission refuse(Reason reason) {
            return new Admission(null, reason, null);
        }

        public boolean ok() {
            return refused == null;
        }
    }

    /**
     * 挖 {@code pos} 能不能进路线,依次问:规格许不许改地形、按位置与按种类禁不禁、物理上挖不挖得了({@link DigRules})、
     * 许可怎么答——拒绝不进,要问的只在 {@code alter=any} 下进。
     */
    public Admission admitDig(WorldView view, BlockPos pos, BlockState state) {
        if (!spec.alter().mayAlter()) {
            return Admission.refuse(Reason.NEEDS_ALTER);
        }
        if (forbids(Use.DIG, pos.asLong()) || spec.bans().breaking().contains(state.getBlock())) {
            return Admission.refuse(Reason.FORBIDDEN);
        }
        Reason physical = DigRules.check(view, body, pos, state, spec.strictLiquidCheck());
        if (physical != null) {
            return Admission.refuse(physical);
        }
        return judge(TerrainPolicy.Change.DIG, pos, state, view);
    }

    /**
     * 往 {@code pos}(此刻是 {@code current})放一块垫路料能不能进路线,依次问:规格许不许改地形、按位置与按种类禁不禁、
     * 身上有没有料、游戏模式与世界边界、这一格放不放得进去({@link Replaceable})、有没有能点的面({@link Faces})、许可怎么答。
     * "不许改地形"与"没有料"分成两个原因,不合成一个。
     */
    public Admission admitPlace(WorldView view, BlockPos pos, BlockState current) {
        if (!spec.alter().mayAlter()) {
            return Admission.refuse(Reason.NEEDS_ALTER);
        }
        if (forbids(Use.PLACE, pos.asLong()) || spec.bans().placingInto().contains(current.getBlock())) {
            return Admission.refuse(Reason.FORBIDDEN);
        }
        if (placing == null) {
            return Admission.refuse(Reason.NO_MATERIALS);
        }
        if (!body.mayEdit()) {
            return Admission.refuse(Reason.EDIT_RESTRICTED);
        }
        if (!Bounds.allowsEdit(view.border(), view, pos)) {
            return Admission.refuse(Reason.OUT_OF_BOUNDS);
        }
        if (!Replaceable.replaceableBy(current, placing)) {
            return Admission.refuse(Reason.NOT_REPLACEABLE);
        }
        if (Faces.against(view, pos, placing).isEmpty()) {
            return Admission.refuse(Reason.NO_FACE);
        }
        return judge(new TerrainPolicy.Change.Place(placing), pos, current, view);
    }

    /**
     * 下落摔不起时在落点 {@code pos}(此刻是 {@code current})倒一桶水接住能不能进路线,依次问:身上有没有一桶水、这个维度倒不
     * 倒得出水(没有就还是摔不起)、规格许不许改地形、按种类禁不禁(水冲掉的那一格原来的方块)、游戏模式与世界边界、这一格
     * 倒不倒得进水、有没有能点的面(落点脚下那块的顶面)、许可怎么答(按往这一格放东西问)。
     *
     * <p>按位置的"放"({@link Use#PLACE})管的是放下之后留在世界上的方块;倒下的水在同一步里就收回,不问它——目标格保护
     * 不许往要站的格里放方块,却不拦倒水接住落进那一格的自己。
     */
    public Admission admitCatch(WorldView view, BlockPos pos, BlockState current) {
        if (!body.carriesWaterBucket() || view.ultraWarm()) {
            return Admission.refuse(Reason.TOO_FAR_TO_FALL);
        }
        if (!spec.alter().mayAlter()) {
            return Admission.refuse(Reason.NEEDS_ALTER);
        }
        if (spec.bans().placingInto().contains(current.getBlock())) {
            return Admission.refuse(Reason.FORBIDDEN);
        }
        if (!body.mayEdit()) {
            return Admission.refuse(Reason.EDIT_RESTRICTED);
        }
        if (!Bounds.allowsEdit(view.border(), view, pos)) {
            return Admission.refuse(Reason.OUT_OF_BOUNDS);
        }
        if (!Replaceable.replaceableBy(current, Blocks.WATER)) {
            return Admission.refuse(Reason.NOT_REPLACEABLE);
        }
        if (Faces.against(view, pos, Blocks.WATER).isEmpty()) {
            return Admission.refuse(Reason.NO_FACE);
        }
        return judge(new TerrainPolicy.Change.Place(Blocks.WATER), pos, current, view);
    }

    private Admission judge(TerrainPolicy.Change change, BlockPos pos, BlockState state, WorldView view) {
        Permit permit = terrain.judge(change, pos.immutable(), state, view);
        return switch (permit) {
            case Permit.Allow allow -> new Admission(permit, null, null);
            case Permit.Ask ask -> spec.alter() == RouteSpec.Alter.ANY
                    ? new Admission(permit, null, null)
                    : Admission.refuse(Reason.NEEDS_CONSENT);
            case Permit.Deny deny -> new Admission(null, Reason.DENIED, deny.reason());
        };
    }

    // ==================== 价钱 ====================

    /**
     * 挖一格:用挑中的工具挖掉它手上要花的刻数(挖到碎,加碎了之后缓手的那几刻,{@link ToolChoice#handTicks}),加规格的挖掘罚分
     * 与这一格的按位置加价;许可要问的乘 {@link ActionCosts#CONSENT_MULTIPLIER}。
     */
    public double digCost(Edit.Dig dig) {
        double cost = tools.handTicks(dig.state(), dig.eyeInWater(), dig.grounded()) + spec.breakPenalty()
                + extra(Use.DIG, dig.pos().asLong());
        return dig.permit() instanceof Permit.Ask ? cost * ActionCosts.CONSENT_MULTIPLIER : cost;
    }

    /**
     * 放一块:规格的放置罚分加这一格的按位置加价,许可要问的乘 {@link ActionCosts#CONSENT_MULTIPLIER};规格要求事后拆回时,
     * 再加上挖掉这块料手上要花的刻数(站在地上、眼睛不在水里挖,{@link ToolChoice#handTicks})。
     */
    public double placeCost(Edit.Place place) {
        double cost = spec.placeCost() + extra(Use.PLACE, place.pos().asLong());
        if (place.permit() instanceof Permit.Ask) {
            cost *= ActionCosts.CONSENT_MULTIPLIER;
        }
        if (spec.takeBack()) {
            cost += tools.handTicks(place.block().defaultBlockState(), false, true);
        }
        return cost;
    }

    /**
     * 倒一桶水接住坠落:与放一块同样的罚分(许可要问的乘 {@link ActionCosts#CONSENT_MULTIPLIER}),再加上落定之后把水收回桶里的
     * 那一下。按位置的"放"加价管的是留在世界上的方块,水当场收回,不加。
     */
    public double catchCost(Edit.Catch caught) {
        double cost = spec.placeCost();
        if (caught.permit() instanceof Permit.Ask) {
            cost *= ActionCosts.CONSENT_MULTIPLIER;
        }
        return cost + ActionCosts.SCOOP_WATER;
    }

    /**
     * 一步里除了身体移动本身以外的价钱,每种走法都一样加:要做的改动、身体新进入的格与落脚那一格的按位置加价、
     * 紧挨着伤身的格走过的加价、落到水里的涉水罚分。
     */
    public double overhead(Maneuver m) {
        double cost = 0;
        for (Edit edit : m.edits()) {
            cost += switch (edit) {
                case Edit.Dig dig -> digCost(dig);
                case Edit.Place place -> placeCost(place);
                case Edit.Door door -> 0;
                case Edit.Catch caught -> catchCost(caught);
            };
        }
        for (long cell : m.cells()) {
            cost += extra(Use.PASS, cell);
        }
        cost += m.exposure() * ActionCosts.EXPOSED_SIDE;
        if (m.support() != null) {
            cost += extra(Use.STAND, m.support().asLong());
        }
        if (m.wading()) {
            cost += spec.wadePenalty();
        }
        return cost;
    }

    // ==================== 生物危险 ====================

    /** 每只生物危险半径里的每一格,身体进去一格加 {@link ActionCosts#DANGER_PER_CELL}。几只重叠就叠加。 */
    static PositionCosts dangerCosts(List<Threat> threats) {
        if (threats.isEmpty()) {
            return PositionCosts.EMPTY;
        }
        PositionCosts.Builder b = PositionCosts.builder();
        for (Threat t : threats) {
            int r = Mth.ceil(t.radius());
            int cx = Mth.floor(t.x());
            int cy = Mth.floor(t.y());
            int cz = Mth.floor(t.z());
            for (int x = cx - r; x <= cx + r; x++) {
                for (int y = cy - r; y <= cy + r; y++) {
                    for (int z = cz - r; z <= cz + r; z++) {
                        if (t.covers(x, y, z)) {
                            b.add(Use.PASS, BlockPos.asLong(x, y, z), ActionCosts.DANGER_PER_CELL);
                        }
                    }
                }
            }
        }
        return b.build();
    }
}
