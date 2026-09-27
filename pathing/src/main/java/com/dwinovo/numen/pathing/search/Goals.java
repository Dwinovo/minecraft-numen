package com.dwinovo.numen.pathing.search;

import java.util.List;

import com.dwinovo.numen.pathing.plan.ActionCosts;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Reach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;

/**
 * 目标族:把"去哪"的各种说法编成 {@link Goal}。到达判定都写在这里,别处不另判;要"靠近就行"就编一个 {@link #near},
 * 模块里没有别的宽限。
 *
 * <p>估价按 {@link ActionCosts} 的估价权重:水平走八方向距离、往上按跳、往下按落,每种目标只估到它自己的边界。
 */
public final class Goals {

    private Goals() {}

    // ==================== 工厂 ====================

    /** 脚正好在这个节点。给了 y 就是那一格。 */
    public static Goal at(BlockPos feet) {
        return new At(feet.immutable());
    }

    /** 那一列,任何高度:只给了 x、z 的去处。 */
    public static Goal column(int x, int z) {
        return new Column(x, z);
    }

    /** 到某一高度:脚所在的格是第 {@code y} 层,任何 x、z。 */
    public static Goal level(int y) {
        return new Level(y);
    }

    /** 靠近:脚所在的格离 {@code pos} 不超过 {@code radius} 格(三维直线距离)。 */
    public static Goal near(BlockPos pos, double radius) {
        return new Near(pos.immutable(), radius);
    }

    /** 环形站位:脚所在的列离 {@code pos} 那一列的水平距离在 {@code [inner, outer]} 之间。 */
    public static Goal ring(BlockPos pos, double inner, double outer) {
        if (inner > outer) {
            throw new IllegalArgumentException("内径大于外径:" + inner + " > " + outer);
        }
        return new Ring(pos.immutable(), inner, outer);
    }

    /** 站上:站在 {@code block} 上,托着脚的就是它。 */
    public static Goal standOn(BlockPos block) {
        return new StandOn(block.immutable());
    }

    /**
     * 贴脸:站在这里手够得着 {@code target}(第 0 层 {@link Reach},与挖、放、交互读同一个"够得着"),而且身体不占着它。
     * 视线不在这里判,到了之后由执行层复核。
     */
    public static Goal reach(BlockPos target, BodyStats body) {
        return new ReachGoal(target.immutable(), body);
    }

    /** 远离一组生物:脚所在的列离每一只的水平距离都不小于它的危险半径。 */
    public static Goal awayFrom(List<Threat> threats) {
        if (threats.isEmpty()) {
            throw new IllegalArgumentException("远离的目标至少要有一只");
        }
        return new AwayFrom(List.copyOf(threats));
    }

    /** 多个目标取其一:到了任何一个就算到;按"走过去加到了再付"挑最便宜的那个。 */
    public static Goal anyOf(List<Goal> goals) {
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("多个目标取其一,至少要有一个");
        }
        return new AnyOf(List.copyOf(goals));
    }

    /** 到了 {@code goal} 之后还要付 {@code cost} 刻:成员各带各的价时,搜索按总价挑。 */
    public static Goal priced(Goal goal, double cost) {
        if (!(cost >= 0) || Double.isInfinite(cost)) {
            throw new IllegalArgumentException("到达价要是非负的有限数:" + cost);
        }
        return new Priced(goal, cost);
    }

    // ==================== 估价 ====================

    /** 从 {@code (x, y, z)} 到 {@code (tx, ty, tz)} 的估价:水平八方向距离按走,竖直往上按跳、往下按落。 */
    static double point(int x, int y, int z, int tx, int ty, int tz) {
        return horizontal(Math.abs(tx - x), Math.abs(tz - z)) + vertical(ty - y);
    }

    private static double horizontal(double dx, double dz) {
        return (Math.min(dx, dz) * Math.sqrt(2) + Math.abs(dx - dz)) * ActionCosts.ESTIMATE_PER_BLOCK;
    }

    private static double vertical(double rise) {
        return rise > 0 ? rise * ActionCosts.ESTIMATE_UP : -rise * ActionCosts.ESTIMATE_DOWN;
    }

    // ==================== 各个目标 ====================

    private record At(BlockPos feet) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return x == feet.getX() && y == feet.getY() && z == feet.getZ();
        }

        @Override
        public double estimate(int x, int y, int z) {
            return point(x, y, z, feet.getX(), feet.getY(), feet.getZ());
        }

        /** 别往要站的两格里放方块,别挖脚下那一格。 */
        @Override
        public PositionCosts protection() {
            return PositionCosts.builder().forbid(Use.PLACE, feet.asLong()).forbid(Use.PLACE, feet.above().asLong())
                    .forbid(Use.DIG, feet.below().asLong()).build();
        }
    }

    private record Column(int cx, int cz) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return x == cx && z == cz;
        }

        @Override
        public double estimate(int x, int y, int z) {
            return horizontal(Math.abs(cx - x), Math.abs(cz - z));
        }
    }

    private record Level(int level) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return y == level;
        }

        @Override
        public double estimate(int x, int y, int z) {
            return vertical(level - y);
        }
    }

    private record Near(BlockPos center, double radius) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return center.distSqr(new BlockPos(x, y, z)) <= radius * radius;
        }

        /** 估到中心而不是球面:半程路线与节点次序都朝同一个点,不随半径抖。 */
        @Override
        public double estimate(int x, int y, int z) {
            return point(x, y, z, center.getX(), center.getY(), center.getZ());
        }
    }

    private record Ring(BlockPos center, double inner, double outer) implements Goal {
        private double distance(int x, int z) {
            double dx = x - center.getX();
            double dz = z - center.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            double d = distance(x, z);
            return d >= inner && d <= outer;
        }

        /** 估到环带而不是中心:太近往外、太远往里,两侧都朝环带递减。 */
        @Override
        public double estimate(int x, int y, int z) {
            double d = distance(x, z);
            double gap = d < inner ? inner - d : d > outer ? d - outer : 0;
            return gap * ActionCosts.ESTIMATE_PER_BLOCK;
        }
    }

    private record StandOn(BlockPos block) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return x == block.getX() && z == block.getZ() && stance.grounded() && stance.supportY() == block.getY();
        }

        @Override
        public double estimate(int x, int y, int z) {
            return point(x, y, z, block.getX(), block.getY() + 1, block.getZ());
        }

        /** 别挖要站上去的那一块,也别往它上面身体要站的两格里放方块。 */
        @Override
        public PositionCosts protection() {
            return PositionCosts.builder().forbid(Use.DIG, block.asLong()).forbid(Use.PLACE, block.above().asLong())
                    .forbid(Use.PLACE, block.above(2).asLong()).build();
        }
    }

    private record ReachGoal(BlockPos target, BodyStats body) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            double feet = stance.feetY();
            return !Clearance.occupies(body, Pose.STANDING, x, feet, z, target)
                    && Reach.reaches(body, Pose.STANDING, x, feet, z, target);
        }

        /** 眼睛(按脚在这一格的底算)到目标那一格的距离超出交互距离的那一截,按水平每格的价钱。 */
        @Override
        public double estimate(int x, int y, int z) {
            double distance = Math.sqrt(new AABB(target).distanceToSqr(Reach.eye(body, Pose.STANDING, x, y, z)));
            return Math.max(0, distance - body.blockReach()) * ActionCosts.ESTIMATE_PER_BLOCK;
        }

        /** 别挖要够的那一格,也别往里放方块把它埋了。 */
        @Override
        public PositionCosts protection() {
            return PositionCosts.builder().forbid(Use.DIG, target.asLong()).forbid(Use.PLACE, target.asLong()).build();
        }
    }

    private record AwayFrom(List<Threat> threats) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            for (Threat t : threats) {
                double dx = x + 0.5 - t.x();
                double dz = z + 0.5 - t.z();
                if (dx * dx + dz * dz < t.radius() * t.radius()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 势场:每一只按"离它的距离是危险半径的几倍"贡献,越近越贵,几只相加——两只一左一右时,直穿哪一只都不便宜。
         * 贴在半径上时每只贡献 {@link ActionCosts#DANGER_PER_CELL},与走进它半径里一格的代价同一个量级。
         */
        @Override
        public double estimate(int x, int y, int z) {
            double sum = 0;
            for (Threat t : threats) {
                double dx = x + 0.5 - t.x();
                double dy = y - t.y();
                double dz = z + 0.5 - t.z();
                double span = Math.max(1, t.radius());
                double ratio = (dx * dx + dy * dy + dz * dz) / (span * span);
                sum += 1 / Math.max(ratio, 1.0E-3);
            }
            return sum * ActionCosts.DANGER_PER_CELL;
        }
    }

    private record AnyOf(List<Goal> members) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            for (Goal g : members) {
                if (g.contains(x, y, z, stance)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public double estimate(int x, int y, int z) {
            double min = Double.POSITIVE_INFINITY;
            for (Goal g : members) {
                min = Math.min(min, g.estimate(x, y, z));
            }
            return min;
        }

        /** 停在这里满足的那些成员里最便宜的到达价。 */
        @Override
        public double arrival(int x, int y, int z, Stance stance) {
            double min = Double.POSITIVE_INFINITY;
            for (Goal g : members) {
                if (g.contains(x, y, z, stance)) {
                    min = Math.min(min, g.arrival(x, y, z, stance));
                }
            }
            return min == Double.POSITIVE_INFINITY ? 0 : min;
        }

        @Override
        public PositionCosts protection() {
            PositionCosts all = PositionCosts.EMPTY;
            for (Goal g : members) {
                all = all.plus(g.protection());
            }
            return all;
        }
    }

    private record Priced(Goal inner, double cost) implements Goal {
        @Override
        public boolean contains(int x, int y, int z, Stance stance) {
            return inner.contains(x, y, z, stance);
        }

        @Override
        public double estimate(int x, int y, int z) {
            return inner.estimate(x, y, z) + cost;
        }

        @Override
        public double arrival(int x, int y, int z, Stance stance) {
            return cost;
        }

        @Override
        public PositionCosts protection() {
            return inner.protection();
        }
    }
}
