package com.dwinovo.numen.core.pathing.spec;

import java.util.Arrays;
import java.util.Set;

import net.minecraft.world.level.block.Block;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 一次导航的路线规格:每次搜索和每次执行都带一份,成本模型只认它。按次传值、不可变,
 * 搜索工作线程只读。四组旋钮:
 * <ol>
 *   <li><b>能力开关与上限</b>——这一格能不能做这种动作:改地形({@link Alter})、疾跑、
 *       跑酷、斜向上下、向下挖、攀藤;最大落差、一条路最多改几格;</li>
 *   <li><b>每类代价</b>——{@link CellClass} 到代价的表,{@link #FORBID} 即排除;</li>
 *   <li><b>按位置与按种类</b>——{@link PositionCosts}(不看方块看坐标),以及
 *       {@link BlockBans}(不看坐标看方块:这些种类不挖、不往里放、不踩);</li>
 *   <li><b>动作代价</b>——放置、挖掘附加、跳跃、涉水四项惩罚,外加默认关的每格噪声。</li>
 * </ol>
 *
 * <p>与 {@code NavSettings} 的服主总开关是两层:总开关是天花板(服主关了谁也挖不了),
 * 规格是这一次在天花板之下实际用到的部分。规格里的每一项都要能在账单里看出它起了什么
 * 作用。{@link #defaults()} 即"只走不改"的出厂值;派生用 {@code withXxx}。
 */
public final class RouteSpec {

    /** 排除:某类格子或某个动作在这条路线上不可用。 */
    public static final double FORBID = COST_INF;
    /** 改动预算的"不限"值。 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /**
     * 走路能不能改世界。{@link #NONE} 是接近类动作的默认——它们的意图是"到那儿去",
     * 不是"改那儿";挖矿、施工天然 {@link #NATURAL};{@link #ANY} 把需要主人同意的格也
     * 算进路线、账单里单列(这一步与 NATURAL 同义,留给权限层)。
     */
    public enum Alter {
        NONE, NATURAL, ANY;

        public boolean mayAlter() {
            return this != NONE;
        }
    }

    private final Alter alter;
    private final boolean sprint;
    private final boolean parkour;
    private final boolean parkourPlace;
    private final boolean parkourAscend;
    private final boolean diagonalAscend;
    private final boolean diagonalDescend;
    private final boolean downward;
    private final boolean climbVines;
    private final boolean strictLiquidCheck;
    private final int maxFallHeightNoWater;
    private final int alterBudget;
    private final double[] cellCosts;
    private final PositionCosts positions;
    private final double placeCost;
    private final double breakPenalty;
    private final double jumpPenalty;
    private final double wadePenalty;
    private final double noise;
    private final BlockBans bans;

    /**
     * 按方块种类的禁令,与 {@link PositionCosts} 互补:位置表回答"这一格",这张表回答
     * "这一种"。三栏各自独立——不挖这些(箱子、原木)、不往这些格里放(水源、高草)、
     * 不踩这些(耕地、作物)。标签展开成成员后存,搜索热路径只做集合查询。
     */
    public record BlockBans(Set<Block> breaking, Set<Block> placingInto, Set<Block> standingOn) {
        public static final BlockBans EMPTY = new BlockBans(Set.of(), Set.of(), Set.of());

        public BlockBans {
            breaking = Set.copyOf(breaking);
            placingInto = Set.copyOf(placingInto);
            standingOn = Set.copyOf(standingOn);
        }

        public boolean isEmpty() {
            return breaking.isEmpty() && placingInto.isEmpty() && standingOn.isEmpty();
        }
    }

    private RouteSpec(Alter alter, boolean sprint, boolean parkour, boolean parkourPlace,
                      boolean parkourAscend, boolean diagonalAscend, boolean diagonalDescend,
                      boolean downward, boolean climbVines, boolean strictLiquidCheck,
                      int maxFallHeightNoWater, int alterBudget, double[] cellCosts,
                      PositionCosts positions, double placeCost, double breakPenalty,
                      double jumpPenalty, double wadePenalty, double noise, BlockBans bans) {
        this.alter = alter;
        this.sprint = sprint;
        this.parkour = parkour;
        this.parkourPlace = parkourPlace;
        this.parkourAscend = parkourAscend;
        this.diagonalAscend = diagonalAscend;
        this.diagonalDescend = diagonalDescend;
        this.downward = downward;
        this.climbVines = climbVines;
        this.strictLiquidCheck = strictLiquidCheck;
        this.maxFallHeightNoWater = maxFallHeightNoWater;
        this.alterBudget = alterBudget;
        this.cellCosts = cellCosts;
        this.positions = positions;
        this.placeCost = placeCost;
        this.breakPenalty = breakPenalty;
        this.jumpPenalty = jumpPenalty;
        this.wadePenalty = wadePenalty;
        this.noise = noise;
        this.bans = bans;
    }

    private static final RouteSpec DEFAULTS = new RouteSpec(
            Alter.NONE, true, false, false, true, false, false, true, false, false,
            3, UNLIMITED, defaultCellCosts(), PositionCosts.EMPTY,
            20.0, 30.0, 2.0, 3.0, 0.0, BlockBans.EMPTY);

    /**
     * 出厂规格:只走不改,可疾跑、可跑酷跳上高一格、可原地向下挖;不跑酷平跳、不斜向上下、
     * 不攀藤;无水落差上限 3;岩浆、危险格、障碍排除,其余类型不另计价;放置 20、挖掘附加 30
     * (≈ 多走 6.5 格:破坏是绕不开时的下策)、起跳 2、涉水 3。
     */
    public static RouteSpec defaults() {
        return DEFAULTS;
    }

    private static double[] defaultCellCosts() {
        double[] costs = new double[CellClass.values().length];
        costs[CellClass.LAVA.ordinal()] = FORBID;
        costs[CellClass.HAZARD.ordinal()] = FORBID;
        costs[CellClass.OBSTACLE.ordinal()] = FORBID;
        return costs;
    }

    // ==================== 能力 ====================

    public Alter alter() {
        return alter;
    }

    public boolean sprint() {
        return sprint;
    }

    /** 跑酷平跳(2-4 格空隙)。 */
    public boolean parkour() {
        return parkour;
    }

    /** 跑酷跳跃中途在落点下方放方块。 */
    public boolean parkourPlace() {
        return parkourPlace;
    }

    /** 跑酷跳上高一格的落点。 */
    public boolean parkourAscend() {
        return parkourAscend;
    }

    public boolean diagonalAscend() {
        return diagonalAscend;
    }

    public boolean diagonalDescend() {
        return diagonalDescend;
    }

    /** 原地向下挖。 */
    public boolean downward() {
        return downward;
    }

    /** 藤蔓算可站的攀爬面。 */
    public boolean climbVines() {
        return climbVines;
    }

    /** 挖掘的液体邻格判定从严:任何相邻液体都禁挖(默认只禁源与横流)。 */
    public boolean strictLiquidCheck() {
        return strictLiquidCheck;
    }

    /** 无水情况下可接受的最大坠落高度。 */
    public int maxFallHeightNoWater() {
        return maxFallHeightNoWater;
    }

    /**
     * 改动预算:整条路挖加放最多几格。规划查询把账单超过预算的候选作废,一条都不剩就报
     * "预算内无路";默认 {@link #UNLIMITED} 即不限。它只在规划时判——被堵后的重算不再核,
     * 实际账单照实记。
     */
    public int alterBudget() {
        return alterBudget;
    }

    /** 是否设了有限的改动预算。 */
    public boolean budgeted() {
        return alterBudget != UNLIMITED;
    }

    // ==================== 每类代价 ====================

    /** 这一类格子的代价;{@link #FORBID} 即排除。 */
    public double cellCost(CellClass cls) {
        return cellCosts[cls.ordinal()];
    }

    // ==================== 按位置 ====================

    public PositionCosts positions() {
        return positions;
    }

    // ==================== 按种类 ====================

    public BlockBans bans() {
        return bans;
    }

    // ==================== 动作代价 ====================

    /** 放置一个方块的罚金(省方块,不鼓励乱放)。 */
    public double placeCost() {
        return placeCost;
    }

    /** 每次挖掘除纯挖掘耗时外的附加罚金。 */
    public double breakPenalty() {
        return breakPenalty;
    }

    /** 每次起跳的罚金。 */
    public double jumpPenalty() {
        return jumpPenalty;
    }

    /** 水面行走每格的罚金。 */
    public double wadePenalty() {
        return wadePenalty;
    }

    /** 每格小噪声,让路线看起来像人走的(默认 0;这一步只存不用)。 */
    public double noise() {
        return noise;
    }

    // ==================== 派生 ====================

    public RouteSpec withAlter(Alter alter) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withSprint(boolean sprint) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withParkour(boolean parkour) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withParkourPlace(boolean parkourPlace) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withParkourAscend(boolean parkourAscend) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withDiagonalAscend(boolean diagonalAscend) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withDiagonalDescend(boolean diagonalDescend) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withDownward(boolean downward) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withClimbVines(boolean climbVines) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withStrictLiquidCheck(boolean strictLiquidCheck) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withMaxFallHeightNoWater(int maxFallHeightNoWater) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withAlterBudget(int alterBudget) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withCellCost(CellClass cls, double cost) {
        double[] costs = Arrays.copyOf(cellCosts, cellCosts.length);
        costs[cls.ordinal()] = cost;
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, costs, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withPositions(PositionCosts positions) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withPlaceCost(double placeCost) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withBreakPenalty(double breakPenalty) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withJumpPenalty(double jumpPenalty) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withWadePenalty(double wadePenalty) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withNoise(double noise) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }

    public RouteSpec withBans(BlockBans bans) {
        return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater,
                alterBudget, cellCosts, positions, placeCost, breakPenalty, jumpPenalty,
                wadePenalty, noise, bans);
    }
}
