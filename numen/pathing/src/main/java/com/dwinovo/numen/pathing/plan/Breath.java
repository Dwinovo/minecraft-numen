package com.dwinovo.numen.pathing.plan;

/**
 * 身体憋气的本钱与原版的掉氧、回气规则,全模块只此一处。规划时搜索沿路一步步推算({@link #after}),憋不住的路不走
 * ({@link #lasts});执行时每一步开始前与执行中的每一刻按身体此刻的真实氧气把这一段水下剩下的部分重算一遍;没路时诊断问"憋得住的话有没有路",
 * 读的都是这里。一步里眼睛换不换得了气是那一步的事实({@link Maneuver#submerged}),要多少刻是那种走法的
 * {@link Move#ticks}。
 *
 * <p>原版 1.21.1:
 * <ul>
 *   <li>眼睛泡在水里、眼睛那一格不是气泡柱时({@code LivingEntity.baseTick}),每刻扣氧气:属性 {@code oxygen_bonus}
 *       (水下呼吸附魔,每级 1)为 b 时,这一刻以 1/(b+1) 的概率扣 1({@code decreaseAirSupply}),平均每刻扣 1/(b+1)——
 *       同样多的氧气能憋 b+1 倍久。规划按这个平均算,随机的出入由 {@link #RESERVE} 兜住;</li>
 *   <li>身上有水下呼吸或潮涌能量效果({@code MobEffectUtil.hasWaterBreathing})时不扣;能在水下呼吸的实体
 *       ({@code canBreatheUnderwater})、无敌的玩家(创造模式)永远不扣;</li>
 *   <li>氧气扣到 -20 时归零并受 2 点溺水伤害,之后每秒一次;</li>
 *   <li>眼睛出了水每刻回 4 点({@code increaseAirSupply}),回满为止——回气要时间,半路露一下头换不满;</li>
 *   <li>戴着海龟壳时,眼睛不在水里的每一刻都把水下呼吸效果续到 200 刻({@code Player.turtleHelmetTick}):
 *       每次下水先有 10 秒不扣氧。</li>
 * </ul>
 *
 * @param supply      此刻的氧气({@code getAirSupply})
 * @param maxSupply   氧气上限(原版玩家 300)
 * @param oxygenBonus 属性 {@code oxygen_bonus}(原版 0)
 * @param shield      此刻水下呼吸类效果还剩几刻;能在水下呼吸、无敌时是正无穷
 * @param turtleShell 头上戴着海龟壳
 */
public record Breath(int supply, int maxSupply, double oxygenBonus, double shield, boolean turtleShell) {

    /** 原版玩家不带任何效果、满氧气时的样子。 */
    public static final Breath VANILLA = new Breath(300, 300, 0, 0, false);
    /** 海龟壳在眼睛出水时把水下呼吸续到的刻数({@code Player.turtleHelmetTick})。 */
    public static final int TURTLE_SHELL_TICKS = 200;
    /** 眼睛出水时每刻回的氧气({@code LivingEntity.increaseAirSupply})。 */
    public static final int REFILL_PER_TICK = 4;
    /**
     * 憋完一段水下至少还要留着能再憋这么多刻:规划的刻数是估的(身体在水里的真实快慢、转弯、被水推一下),水下呼吸附魔的
     * 扣氧是随机的;执行时按真实氧气每一刻重算,这一段足够让她在估错时游完这一段,不必指望本能把她捞出去。三秒,
     * 原版满氧气的五分之一。
     */
    public static final double RESERVE = 60;
    /** 搜索按憋气分节点的档宽({@link #band}):一秒。 */
    private static final int BAND = 20;

    /** 永远憋得住:诊断问"憋得住的话有没有路"时的起点。 */
    public static final Air UNLIMITED = new Air(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0);

    /**
     * 沿一条路推到某一步时身体憋气的样子。
     *
     * @param lungs  自己肺里的氧气还能在水下撑几刻:氧气乘以 {@code oxygen_bonus + 1}
     * @param shield 水下呼吸类效果还剩几刻;正无穷是永远不扣氧
     * @param held   这一段水下已经憋了几刻;眼睛出水为 0
     */
    public record Air(double lungs, double shield, double held) {

        /** 从这里起还能在水下撑几刻,撑到氧气见底(再往后就开始溺水)。 */
        public double left() {
            return shield + lungs;
        }
    }

    public Breath {
        if (maxSupply <= 0) {
            throw new IllegalArgumentException("氧气上限要是正数:" + maxSupply);
        }
    }

    /** 同样多的氧气能憋几倍久:{@code oxygen_bonus + 1}。 */
    private double stretch() {
        return Math.max(0, oxygenBonus) + 1;
    }

    /** 肺里满氧气时能在水下撑几刻。 */
    private double capacity() {
        return maxSupply * stretch();
    }

    /** 此刻的样子:还没憋。 */
    public Air now() {
        return new Air(supply * stretch(), shield, 0);
    }

    /**
     * 从 {@code before} 起走一步、用 {@code ticks} 刻之后憋气的样子。{@code submerged} 时整步眼睛在水里:先耗水下呼吸
     * 类效果,耗完再耗肺里的氧气;否则整步在换气:肺里每刻回 {@link #REFILL_PER_TICK} 点氧气,回满为止,水下呼吸类效果照常
     * 流逝(戴海龟壳的续到 {@link #TURTLE_SHELL_TICKS})。结果可以扣到见底以下——够不够由 {@link #lasts} 判。
     */
    public Air after(Air before, boolean submerged, double ticks) {
        if (submerged) {
            double shielded = Math.min(before.shield(), ticks);
            return new Air(before.lungs() - (ticks - shielded), before.shield() - shielded, before.held() + ticks);
        }
        double lungs = Math.min(capacity(), before.lungs() + REFILL_PER_TICK * stretch() * ticks);
        double shield = Math.max(before.shield() - ticks, turtleShell ? TURTLE_SHELL_TICKS : 0);
        return new Air(lungs, shield, 0);
    }

    /** 走到这里还憋得住:肺里的氧气还能再撑 {@link #RESERVE} 刻。 */
    public boolean lasts(Air air) {
        return air.lungs() >= RESERVE;
    }

    /**
     * 气息平稳:肺里满、没在憋,水下呼吸类效果也不在流逝(没有、戴海龟壳续满了,或永远不扣)。这样的身体走一步不下水,
     * 憋气的样子不变,不必推算。
     */
    public boolean rested(Air air) {
        return air.held() == 0 && air.lungs() >= capacity()
                && (Double.isInfinite(air.shield()) || air.shield() == (turtleShell ? TURTLE_SHELL_TICKS : 0));
    }

    /** 从 {@code air} 起还能安全地憋几刻:撑到只剩 {@link #RESERVE} 为止。 */
    public double spare(Air air) {
        return air.shield() + air.lungs() - RESERVE;
    }

    /**
     * 搜索按憋气的样子分节点的档:肺里满、没在憋的都是 0 档(水下呼吸效果剩多少不分——同一格先到的剩得多);其余按还能撑
     * 几刻每 {@value #BAND} 刻一档。同一格同一档只留代价低的那条,留下的都是真走得出的样子,只会少留路,不会留下憋不住的。
     */
    public int band(Air air) {
        if (Double.isInfinite(air.shield()) || air.held() == 0 && air.lungs() >= capacity()) {
            return 0;
        }
        return 1 + (int) Math.min(1 << 20, Math.max(0, air.left()) / BAND);
    }
}
