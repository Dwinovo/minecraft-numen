package com.dwinovo.numen.pathing.spec;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import com.dwinovo.numen.pathing.world.Semantics.Kind;

/**
 * 一次导航的路线规格:每次搜索和每次执行各带一份,成本模型只认它。按次传值、不可变,搜索线程只读。四组旋钮,每组一个出处:
 * <ol>
 *   <li><b>能力开关与上限</b>——能不能改地形({@link Alter})、疾跑、跑酷、斜向上下、原地向下挖、攀藤;无水时的最大落差、
 *       一条路最多改几格;</li>
 *   <li><b>排除的格子种类</b>——{@link #excluded()}:这条路线不站上、不穿过这些语义种类的格子。每类格子只有"排除"这一种
 *       处置,不按种类另外计价;</li>
 *   <li><b>按位置与按种类</b>——{@link PositionCosts}(看坐标)与 {@link BlockBans}(看方块种类);</li>
 *   <li><b>动作代价</b>——放置、挖掘、起跳、涉水四项罚分;以及路上放下的块事后要不要拆掉({@link #takeBack()}),
 *       要拆的话放一块的价钱连拆它一起算。</li>
 * </ol>
 *
 * <p>服主总开关是上限,规格只能在其内收紧;规格里的每一项都要能在账单里看出它起了什么作用。{@link #defaults()} 是
 * "只走不改"的出厂值,改动走 {@link #edit()}。
 *
 * @param alter                走路能不能改世界
 * @param sprint               可以疾跑
 * @param parkour              可以跑酷平跳(2–4 格的空隙)
 * @param parkourPlace         跑酷跳到半空时在落点下方放方块
 * @param parkourAscend        跑酷跳上高一格的落点
 * @param diagonalAscend       可以斜着上一级
 * @param diagonalDescend      可以斜着下一级
 * @param downward             可以原地向下挖
 * @param climbVines           藤蔓算可攀爬的面
 * @param strictLiquidCheck    挖掘时邻格有任何液体都不挖(否则只忌源头与横流)
 * @param maxFallHeightNoWater 下面没有水时愿意跳下的最大落差
 * @param alterBudget          整条路挖加放最多几格,{@link #UNLIMITED} 即不限;只在规划时核
 * @param excluded             排除的格子种类
 * @param positions            按坐标的代价与禁令
 * @param bans                 按方块种类的禁令
 * @param placeCost            放一块的罚分(省方块,不鼓励乱放)
 * @param breakPenalty         挖一块在挖掘耗时之外另加的罚分
 * @param jumpPenalty          每次起跳的罚分
 * @param wadePenalty          水里走一格的罚分
 * @param takeBack             路上放下的方块事后都要拆掉
 */
public record RouteSpec(Alter alter, boolean sprint, boolean parkour, boolean parkourPlace, boolean parkourAscend,
                        boolean diagonalAscend, boolean diagonalDescend, boolean downward, boolean climbVines,
                        boolean strictLiquidCheck, int maxFallHeightNoWater, int alterBudget, Set<Kind> excluded,
                        PositionCosts positions, BlockBans bans, double placeCost, double breakPenalty,
                        double jumpPenalty, double wadePenalty, boolean takeBack) {

    /** 改动预算的"不限"值。 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /**
     * 走路能不能改世界。{@link #NONE} 是接近类动作的默认——它们的意图是"到那儿去",不是"改那儿";挖矿、施工天然
     * {@link #NATURAL};{@link #ANY} 把需要主人同意的格也算进路线,账单里列出每一格为什么要同意。
     */
    public enum Alter {
        NONE, NATURAL, ANY;

        public boolean mayAlter() {
            return this != NONE;
        }
    }

    /**
     * 出厂规格:只走不改,可疾跑、可跑酷跳上高一格、可原地向下挖;不跑酷平跳、不斜向上下、不攀藤;无水落差上限 3;
     * 排除岩浆、危险、机关(压力板、绊线)、易碎(耕地、海龟蛋)——后两类会被踩坏或会触发,默认不踩,规格可以放开;
     * 放置 20、挖掘另加 30(约等于多走 6.5 格:破坏是绕不开时的下策)、起跳 2、涉水 3。
     */
    private static final RouteSpec DEFAULTS = new RouteSpec(
            Alter.NONE, true, false, false, true, false, false, true, false, false,
            3, UNLIMITED, EnumSet.of(Kind.LAVA, Kind.HAZARD, Kind.TRIGGER, Kind.FRAGILE),
            PositionCosts.EMPTY, BlockBans.EMPTY, 20.0, 30.0, 2.0, 3.0, false);

    public RouteSpec {
        Objects.requireNonNull(alter, "alter");
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(bans, "bans");
        excluded = Collections.unmodifiableSet(excluded.isEmpty() ? EnumSet.noneOf(Kind.class) : EnumSet.copyOf(excluded));
        if (maxFallHeightNoWater < 0) {
            throw new IllegalArgumentException("最大落差不能为负:" + maxFallHeightNoWater);
        }
        if (alterBudget < 0) {
            throw new IllegalArgumentException("改动预算不能为负:" + alterBudget);
        }
        requirePenalty("placeCost", placeCost);
        requirePenalty("breakPenalty", breakPenalty);
        requirePenalty("jumpPenalty", jumpPenalty);
        requirePenalty("wadePenalty", wadePenalty);
    }

    private static void requirePenalty(String name, double value) {
        if (!(value >= 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " 要是非负的有限数:" + value);
        }
    }

    public static RouteSpec defaults() {
        return DEFAULTS;
    }

    /** 这条路线排除不排除这一种格子。 */
    public boolean excludes(Kind kind) {
        return excluded.contains(kind);
    }

    /** 一格有这些种类,这条路线排除它吗:任何一种被排除,整格就排除。 */
    public boolean excludesAny(Set<Kind> kinds) {
        return !Collections.disjoint(excluded, kinds);
    }

    /** 是否设了有限的改动预算。 */
    public boolean budgeted() {
        return alterBudget != UNLIMITED;
    }

    /** 以这份规格为底改几项。 */
    public Builder edit() {
        return new Builder(this);
    }

    /** 在一份规格上改几项;{@link #build()} 出一份新的,原来那份不变。 */
    public static final class Builder {
        private Alter alter;
        private boolean sprint;
        private boolean parkour;
        private boolean parkourPlace;
        private boolean parkourAscend;
        private boolean diagonalAscend;
        private boolean diagonalDescend;
        private boolean downward;
        private boolean climbVines;
        private boolean strictLiquidCheck;
        private int maxFallHeightNoWater;
        private int alterBudget;
        private final EnumSet<Kind> excluded;
        private PositionCosts positions;
        private BlockBans bans;
        private double placeCost;
        private double breakPenalty;
        private double jumpPenalty;
        private double wadePenalty;
        private boolean takeBack;

        private Builder(RouteSpec from) {
            alter = from.alter;
            sprint = from.sprint;
            parkour = from.parkour;
            parkourPlace = from.parkourPlace;
            parkourAscend = from.parkourAscend;
            diagonalAscend = from.diagonalAscend;
            diagonalDescend = from.diagonalDescend;
            downward = from.downward;
            climbVines = from.climbVines;
            strictLiquidCheck = from.strictLiquidCheck;
            maxFallHeightNoWater = from.maxFallHeightNoWater;
            alterBudget = from.alterBudget;
            excluded = from.excluded.isEmpty() ? EnumSet.noneOf(Kind.class) : EnumSet.copyOf(from.excluded);
            positions = from.positions;
            bans = from.bans;
            placeCost = from.placeCost;
            breakPenalty = from.breakPenalty;
            jumpPenalty = from.jumpPenalty;
            wadePenalty = from.wadePenalty;
            takeBack = from.takeBack;
        }

        public Builder alter(Alter alter) {
            this.alter = alter;
            return this;
        }

        public Builder sprint(boolean sprint) {
            this.sprint = sprint;
            return this;
        }

        public Builder parkour(boolean parkour) {
            this.parkour = parkour;
            return this;
        }

        public Builder parkourPlace(boolean parkourPlace) {
            this.parkourPlace = parkourPlace;
            return this;
        }

        public Builder parkourAscend(boolean parkourAscend) {
            this.parkourAscend = parkourAscend;
            return this;
        }

        public Builder diagonalAscend(boolean diagonalAscend) {
            this.diagonalAscend = diagonalAscend;
            return this;
        }

        public Builder diagonalDescend(boolean diagonalDescend) {
            this.diagonalDescend = diagonalDescend;
            return this;
        }

        public Builder downward(boolean downward) {
            this.downward = downward;
            return this;
        }

        public Builder climbVines(boolean climbVines) {
            this.climbVines = climbVines;
            return this;
        }

        public Builder strictLiquidCheck(boolean strictLiquidCheck) {
            this.strictLiquidCheck = strictLiquidCheck;
            return this;
        }

        public Builder maxFallHeightNoWater(int maxFallHeightNoWater) {
            this.maxFallHeightNoWater = maxFallHeightNoWater;
            return this;
        }

        public Builder alterBudget(int alterBudget) {
            this.alterBudget = alterBudget;
            return this;
        }

        /** 排除这一种格子。 */
        public Builder exclude(Kind kind) {
            excluded.add(kind);
            return this;
        }

        /** 不再排除这一种格子(例如放开默认不踩的耕地)。 */
        public Builder allow(Kind kind) {
            excluded.remove(kind);
            return this;
        }

        public Builder positions(PositionCosts positions) {
            this.positions = positions;
            return this;
        }

        public Builder bans(BlockBans bans) {
            this.bans = bans;
            return this;
        }

        public Builder placeCost(double placeCost) {
            this.placeCost = placeCost;
            return this;
        }

        public Builder breakPenalty(double breakPenalty) {
            this.breakPenalty = breakPenalty;
            return this;
        }

        public Builder jumpPenalty(double jumpPenalty) {
            this.jumpPenalty = jumpPenalty;
            return this;
        }

        public Builder wadePenalty(double wadePenalty) {
            this.wadePenalty = wadePenalty;
            return this;
        }

        public Builder takeBack(boolean takeBack) {
            this.takeBack = takeBack;
            return this;
        }

        public RouteSpec build() {
            return new RouteSpec(alter, sprint, parkour, parkourPlace, parkourAscend, diagonalAscend,
                    diagonalDescend, downward, climbVines, strictLiquidCheck, maxFallHeightNoWater, alterBudget,
                    excluded, positions, bans, placeCost, breakPenalty, jumpPenalty, wadePenalty, takeBack);
        }
    }
}
