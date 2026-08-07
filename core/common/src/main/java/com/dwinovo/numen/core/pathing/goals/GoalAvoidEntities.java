package com.dwinovo.numen.core.pathing.goals;

/**
 * 躲开一组威胁:离每个威胁都拉开 {@code distance} 即到达,而启发式是一片<b>势场</b>——
 * 越靠近越贵,靠得越近涨得越快。
 *
 * <h2>为什么是势场而不是"背对着跑"</h2>
 * {@link GoalRunAway} 的启发式是"到最近威胁点的距离取负",于是它只认最近的那一个,
 * 别的威胁在它眼里不存在——两只怪一左一右时,它会径直穿过其中一只跑向另一边。势场
 * 把每个威胁的贡献加起来,绕开才便宜,穿过去很贵。
 *
 * <h2>权重</h2>
 * {@code weight} 是"这个威胁比寻常危险几倍"。同样的距离,权重越大代价越低、势能越高,
 * 于是搜索更愿意多绕几格。爬行者点火与否就靠它区分——见调用方。
 *
 * <h2>快照,不是活引用</h2>
 * 威胁的坐标在<b>开路那一刻</b>抄下来,搜索过程中不再变。A* 要求同一个格子在一次搜索里
 * 估价恒定;若启发式跟着实体实时移动,同一格先后估出不同的值,搜出来的路径就不再有
 * 任何最优性保证,极端情况下还会来回打转。位置变旧了由重规划解决,不由估价函数解决。
 */
public class GoalAvoidEntities implements Goal {

    /**
     * 一个威胁的快照。
     *
     * @param weight    ≥ 1,越大越危险。同样的距离,权重越大势能越高,她越舍得绕远
     * @param mustClear 要不要为它拉开整个安全距离才算脱身。<b>正在追她的</b>是 true;
     *                  <b>还没盯上她的</b>是 false —— 路过要绕开(否则她逃跑时会从一只
     *                  发呆的史莱姆身上碾过去),但不该为它多跑十几格
     */
    public record Threat(int x, int y, int z, double weight, boolean mustClear) {
        public Threat {
            if (weight <= 0.0) {
                throw new IllegalArgumentException("威胁权重必须为正:" + weight);
            }
        }

        /** 默认是"必须拉开"——追着她的那种。 */
        public Threat(int x, int y, int z, double weight) {
            this(x, y, z, weight, true);
        }
    }

    /**
     * 站在威胁身上时的势能。取一个明确的大数而不是让 1/0 变成无穷——无穷会污染
     * 整张估价表的比较(任何两个"贴脸"格子都成了平手),有限大数则仍然可比。
     */
    static final double ON_TOP_OF_THREAT = 1000.0;

    private final Threat[] threats;
    private final double distance;
    private final double penaltyFactor;

    /**
     * @param distance      离每个威胁多远算脱身
     * @param penaltyFactor 势场的强度。<b>调大</b>:她更坚决地绕开,极端时宁可挖直线也不
     *                      走近路;<b>调小</b>:她会为了近路从威胁边上擦过去,小到一定程度
     *                      就等于直接无视
     * @param threats       至少一个
     */
    public GoalAvoidEntities(double distance, double penaltyFactor, Threat... threats) {
        if (threats.length == 0) {
            throw new IllegalArgumentException("躲避目标至少需要一个威胁");
        }
        this.threats = threats;
        this.distance = distance;
        this.penaltyFactor = penaltyFactor;
    }

    /**
     * 站在 {@code (x, z)} 这一格,算不算离 {@code (threatX, threatZ)} 够远。
     *
     * <p><b>这是"够不够远"的唯一定义</b>,目标与调用方都问它。曾经两边各算各的:判"要不要退"
     * 用的是精确三维距离,判"退到了没有"用的是方块格水平距离、而且门槛还被截成整数
     * ({@code (int) 7.5² = 56})。于是同一刻一个说"还在爆炸范围里",另一个说"你已经站在
     * 合格的格子上了"——导航一建就到达,她原地打转。
     */
    public static boolean clearOf(int x, int z, int threatX, int threatZ, double distance) {
        double dx = x - threatX;
        double dz = z - threatZ;
        return dx * dx + dz * dz >= distance * distance;
    }

    /**
     * 离<b>每个追着她的</b>威胁都够远才算脱身。旁观的敌对生物只进估价、不进这里——
     * 否则一片沼泽里的史莱姆会让"脱身"永远不成立,她就一直跑到寻路失败为止。
     *
     * <p>竖直不计:爬行者的爆炸是球形的,头顶三格并不安全。
     */
    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Threat t : threats) {
            if (t.mustClear() && !clearOf(x, z, t.x(), t.z(), distance)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double sum = 0.0;
        for (Threat t : threats) {
            double dx = x - t.x();
            double dy = y - t.y();
            double dz = z - t.z();
            double cost = (dx * dx + dy * dy + dz * dz) / t.weight();
            sum += cost <= 0.0 ? ON_TOP_OF_THREAT : 1.0 / cost;
        }
        return sum / threats.length * penaltyFactor;
    }

    @Override
    public String toString() {
        return String.format("GoalAvoidEntities{n=%d,dist=%.1f,k=%.1f}",
                threats.length, distance, penaltyFactor);
    }
}
