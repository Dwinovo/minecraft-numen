package com.dwinovo.numen.core.pathing.goals;

/**
 * 躲开一组威胁:离每个都出了<b>它自己的危险半径</b>即到达,而启发式是一片<b>势场</b>——
 * 越靠近越贵,靠得越近涨得越快。
 *
 * <h2>为什么是势场而不是"背对着跑"</h2>
 * {@link GoalRunAway} 的启发式是"到最近威胁点的距离取负",于是它只认最近的那一个,
 * 别的威胁在它眼里不存在——两只怪一左一右时,它会径直穿过其中一只跑向另一边。势场
 * 把每个威胁的贡献加起来,绕开才便宜,穿过去很贵。
 *
 * <h2>半径是每只自己的</h2>
 * 蜘蛛比僵尸够得远大半格,爬行者点着之后够得远好几格。用一个统一的数只能取最大值,
 * 于是她躲僵尸也按蜘蛛的距离躲。半径由 {@code Menace.dangerRadius} 从碰撞箱推。
 *
 * <h2>坐标</h2>
 * 威胁用<b>实体真实坐标</b>,候选格用<b>格心</b>。半径里已经含了格心到格内最远点那半格,
 * 所以"按格心算出来安全"等价于"实际位置也安全"。两边各用各的度量时,判据说"快躲"、
 * 目标说"你已经到位了",导航一建就到达、一步不走,她站在原地挨打。
 *
 * <h2>快照,不是活引用</h2>
 * 威胁的坐标在<b>开路那一刻</b>抄下来,搜索过程中不再变。A* 要求同一个格子在一次搜索里
 * 估价恒定;若启发式跟着实体实时移动,同一格先后估出不同的值,搜出来的路径就不再有任何
 * 最优性保证。位置变旧了由重规划解决({@code PlayerNav} 的活目标节拍),不由估价函数解决。
 */
public class GoalAvoidEntities implements Goal {

    /**
     * 一个威胁的快照。
     *
     * <h2>两个距离,两件事</h2>
     * <b>它们不能是同一个数</b>——我合过一次,后果是逃跑时惩罚球半径变成三十二格,
     * {@code applySpherical} 要枚举 65³ ≈ 二十七万格,乘上七八只怪两百多万次插表,
     * 每次重规划一遍,游戏当场卡死。
     *
     * @param radius    <b>多近算贵</b>:危险半径,决定边成本惩罚球的大小与势场的形状。
     *                  两三格,从碰撞箱推,见 {@code Menace.dangerRadius}
     * @param clearance <b>多远算脱身</b>:到达条件。拉扯时等于 {@code radius}(退出去就能接着
     *                  打),逃跑时是一个统一的大数({@code Menace.FLEE_DISTANCE})
     */
    public record Threat(double x, double y, double z, double radius, double clearance) {
        public Threat {
            if (radius < 0.0 || clearance < 0.0) {
                throw new IllegalArgumentException("距离不能为负:" + radius + " / " + clearance);
            }
        }

        /** 拉扯:退出危险半径就算脱身,两者同一个数。 */
        public Threat(double x, double y, double z, double radius) {
            this(x, y, z, radius, radius);
        }

        /** 逃跑:惩罚球还是那么大,但要拉开到这么远才算脱身。 */
        public Threat withClearance(double newClearance) {
            return new Threat(x, y, z, radius, newClearance);
        }
    }

    /**
     * 站在威胁身上时的势能。取一个明确的大数而不是让 1/0 变成无穷——无穷会污染
     * 整张估价表的比较(任何两个"贴脸"格子都成了平手),有限大数则仍然可比。
     */
    static final double ON_TOP_OF_THREAT = 1000.0;

    private final Threat[] threats;
    private final double penaltyFactor;

    /**
     * @param penaltyFactor 势场的强度。<b>调大</b>:她更坚决地绕开;<b>调小</b>:她会为了近路
     *                      从威胁边上擦过去。不能大过路程量级——势场只进估价不进边成本,
     *                      而估价必须是剩余成本的下界 A* 才敢剪枝
     * @param threats       至少一个
     */
    public GoalAvoidEntities(double penaltyFactor, Threat... threats) {
        if (threats.length == 0) {
            throw new IllegalArgumentException("躲避目标至少需要一个威胁");
        }
        this.threats = threats;
        this.penaltyFactor = penaltyFactor;
    }

    /**
     * {@code (x, z)} 这个点算不算离 {@code (threatX, threatZ)} 够远。
     *
     * <p><b>这是"够不够远"的唯一定义</b>,目标与调用方都问它。曾经两边各算各的:判"要不要退"
     * 用的是精确三维距离,判"退到了没有"用的是方块格水平距离、而且门槛还被截成整数。
     * 于是同一刻一个说"还在危险里",另一个说"你已经站在合格的格子上了"——导航一建就到达,
     * 她原地打转。
     */
    public static boolean clearOf(double x, double z, double threatX, double threatZ,
                                  double radius) {
        double dx = x - threatX;
        double dz = z - threatZ;
        return dx * dx + dz * dz >= radius * radius;
    }

    /** 这一批威胁。搜索器拿它建<b>边成本</b>的惩罚球,见 {@code Avoidance.forGoal}。 */
    public Threat[] threats() {
        return threats.clone();
    }

    /** 离<b>每一个</b>威胁都拉开了 {@code clearance} 才算脱身。竖直不计:爆炸是球形的。 */
    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Threat t : threats) {
            if (!clearOf(x + 0.5, z + 0.5, t.x(), t.z(), t.clearance())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 势场:<b>往哪边走算是"更安全"</b>。距离按危险半径的倍数折算,所以贴在半径边上的一只
     * 蜘蛛和一只僵尸势能相同——危险程度本来就写在半径里,不需要另开一个权重字段。
     *
     * <h2>这里是估价,不是代价</h2>
     * "走进危险格子要多花钱"那件事已经搬去<b>边成本</b>了({@code Avoidance.forGoal},乘在
     * {@code actionCost} 上)。留在这里的势场只对<b>没有吸引项</b>的目标(退避、逃跑)有意义
     * ——它们没有"要去的地方",这片势场就是唯一的方向指示。有吸引项的站位目标
     * ({@code GoalApproachAvoiding})不叠这一层:估价必须是剩余成本的下界,掺进不随接近目标
     * 而下降的项,A* 就不敢剪枝了。
     *
     * <h2>相加,不取平均</h2>
     * 曾经这里除以威胁个数。后果是<b>人越多,每一只越不值得躲</b>:绕开一只贴脸的大史莱姆要
     * 多走一格(路程成本约 4.6),而场上四只时绕开省下的势能只有 4.49 —— 恰好压在天平上,
     * 于是"有时候绕得开,有时候直接从它身上穿过去"。穿过去就被 2.04 格宽的碰撞箱顶住,
     * 走不动、每刻挨接触伤害,实测有效血量从 20 直接掉到 3。
     *
     * <p>危险本来就叠加:四只围着比一只危险,不是一样危险。
     */
    @Override
    public double heuristic(int x, int y, int z) {
        double sum = 0.0;
        for (Threat t : threats) {
            double dx = x + 0.5 - t.x();
            double dy = y - t.y();
            double dz = z + 0.5 - t.z();
            double span = Math.max(1.0, t.radius());
            double cost = (dx * dx + dy * dy + dz * dz) / (span * span);
            sum += cost <= 0.0 ? ON_TOP_OF_THREAT : 1.0 / cost;
        }
        return sum * penaltyFactor;
    }

    @Override
    public String toString() {
        return String.format("GoalAvoidEntities{n=%d,k=%.0f}", threats.length, penaltyFactor);
    }
}
