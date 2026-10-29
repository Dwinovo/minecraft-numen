package com.dwinovo.numen.pathing.search;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.CellFlags;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 竖直方向的额外代价下界:从节点走到目标,脚所在的格要升或降几层,每一层至少比"按疾跑走过这一步的水平距离"多花多少刻。
 * 估价 = 水平底价({@link Goal#estimate})+ 挖开的额外代价({@link Burial})+ 这里的竖直额外代价({@link Estimate})。
 *
 * <p>怎么算:放宽问题的精确解。放宽成水平上随便走、不管站不站得住、不计挖,竖直每层各付各的,每一步付的额外代价是
 * "这一步真实代价减去疾跑走完它的水平距离"的下界,各种走法逐一核对过({@link EstimateConsistencyTest}):
 * <ul>
 *   <li><b>往上一个节点</b>:跳上去的一步(上一级)至少一个起跳间隔({@link Kinematics#jumpCycleTicks})加规格的起跳罚分,
 *       水平挪一格,水平底价已经付了一个疾跑一格,剩下的是额外代价(垫柱、攀爬、游往上更贵,都不比它低)。
 *       脚只抬半格也升一个节点的台阶、半砖不用跳,耗时只是一步走路,额外代价为零——只要快照里<b>这一层</b>出现过这样的落脚
 *       (顶面离下一格底不到迈步高度的方块:{@link Footing#tops});规格许跑酷时,跑酷落在高一级上,空中的工夫由跨过的几格水平距离
 *       付了,只剩起跳罚分;</li>
 *   <li><b>往下 {@code n} 个节点</b>:一次落脚(下一级、下落)是走出边沿再落到地上,至少付走出边沿的工夫加落下去的工夫
 *       ({@link Kinematics#fallLandingTicks})减去水平底价;规格许挖时,向下挖只付落下去的工夫。落差至少是节点数减去落脚那一层
 *       落脚高度的小数部分,最高是身体在规格愿意承受的摔伤下换算出的落差({@link CostModel#maxFallDrop});落脚那一层出现过液体、
 *       可攀爬的方块(活板门也可能接在梯子上)、落上去比石头摔得轻的方块(干草、床、黏液……),而且那一格头顶空着的格数不少于落差(身体落得进去:
 *       被屋顶盖住的洞穴水潭从高处落不进去),或身上带着水桶(倒水接住),落脚不受这个上限。落 {@code n} 个节点最少付多少,是把 {@code n} 拆成若干次落脚、每次付的和最小。
 *       斜走不升降节点,它的升降落差由跳与落带过。</li>
 * </ul>
 * 目标交出到了时脚所在的高度范围({@link Goal#heights()}):节点比它低就要升,比它高就要降,在范围里不用。没有范围的目标不加。
 * 表是问到哪一层才填到哪一层:快照里有这些方块的层(只扫调色板里有它们的方块段)按区段高度一带一带读,没问到的层一格不读;
 * 填出来的数与一开始把整张表填满逐位相同。
 */
final class Vertical {

    /** 不加。 */
    static final Vertical NONE = new Vertical();

    private final CostModel model;
    private final BodyStats body;
    private final Levels levels;
    /** 表的下标 0 对应的层。 */
    private final int base;
    private final int top;
    private final int lo;
    private final int hi;
    /** 往上一个节点至少多付的刻数。 */
    private final double jump;
    private final double sprint;
    private final boolean bucket;
    /** 落在硬地上最高能落多少:身体与规格合起来的摔落上限。 */
    private final double fallLimit;
    /** {@code up[y - base]}:节点在第 {@code y} 层(低于 {@code lo})走到 {@code lo} 层至少多花的刻数;只填到 {@code upFrom}。 */
    private final double[] up;
    private int upFrom;
    private double upSum;
    /** {@code down[y - base]}:节点在第 {@code y} 层(高于 {@code hi})落到 {@code hi} 层至少多花的刻数;只填到 {@code downTo}。 */
    private final double[] down;
    private int downTo;
    /** {@code fresh[k]}:落脚高度没有小数部分时,一次落 {@code k} 个节点的落脚多付的刻数;还没算过为 NaN。 */
    private final double[] fresh;

    private Vertical() {
        this(null, null, null, 0, 0, 0, 0);
    }

    private Vertical(CostModel model, BodyStats body, Levels levels, int base, int top, int lo, int hi) {
        this.model = model;
        this.body = body;
        this.levels = levels;
        this.base = base;
        this.top = top;
        this.lo = lo;
        this.hi = hi;
        if (levels == null) {
            this.jump = 0;
            this.sprint = 0;
            this.bucket = false;
            this.fallLimit = 0;
        } else {
            double penalty = model.spec().jumpPenalty();
            double jumping = Math.max(0, Kinematics.jumpCycleTicks(body, 1.0, body.stepHeight()) + penalty - Kinematics.sprintTicksPerBlock(body));
            this.jump = model.spec().parkour() ? Math.min(jumping, penalty) : jumping;
            this.sprint = Kinematics.sprintTicksPerBlock(body);
            this.bucket = model.body().carriesWaterBucket();
            this.fallLimit = model.maxFallDrop();
        }
        this.up = new double[Math.max(0, top - base)];
        this.upFrom = Math.min(lo, top);
        this.down = new double[Math.max(0, top - base)];
        this.downTo = Math.max(hi + 1, base) - 1;
        this.fresh = new double[Math.max(0, top - base) + 1];
        Arrays.fill(fresh, Double.NaN);
    }

    /**
     * 脚在第 {@code y} 层的节点走进目标,竖直方向至少还要多付的刻数。表不是一开始就填满的:问到哪一层,才把它和它到目标之间的层读出来
     * ({@link Levels})、算到那一层;所以只能在搜索的那一个线程上用。
     */
    double floor(int y) {
        if (levels == null) {
            return 0;
        }
        return y < lo ? rise(y) : y > hi ? fall(y) : 0;
    }

    /** 这次搜索的竖直额外代价:读 {@code view} 里的方块,按 {@code model} 的身体与规格,目标交出的高度范围。 */
    static Vertical of(SearchView view, CostModel model, Goal goal) {
        Goal.Heights heights = goal.heights();
        if (heights == null) {
            return NONE;
        }
        BodyStats body = model.body().stats();
        int base = view.getMinBuildHeight();
        return new Vertical(model, body, new Levels(view, model.body()), base, base + view.getHeight(), heights.lo(), heights.hi());
    }

    /** 往上:从目标那一层往下一层层累加,累加的先后与逐层填满整张表时一样。 */
    private double rise(int y) {
        if (y < base || y >= Math.min(lo, top)) {
            return 0;
        }
        while (upFrom > y) {
            upFrom--;
            upSum += levels.smoothRise(upFrom) ? 0 : jump;
            up[upFrom - base] = upSum;
        }
        return up[y - base];
    }

    /** 往下:从目标那一层往上一层层算,每一层只读比它低的层。 */
    private double fall(int y) {
        if (y < Math.max(hi + 1, base) || y >= top) {
            return 0;
        }
        while (downTo < y) {
            downTo++;
            down[downTo - base] = cheapestFall(downTo);
        }
        return down[y - base];
    }

    private double cheapestFall(int y) {
        double best = Double.POSITIVE_INFINITY;
        for (int k = 1; y - k >= hi; k++) {
            int land = y - k;
            double fraction = levels.fraction(land);
            if (!bucket && k > Math.max(1, Math.floor(fallLimit + fraction + Footing.EPSILON)) && !levels.catches(land, k)) {
                continue;
            }
            double once = fraction == 0 ? fresh(k) : landing(k, fraction);
            best = Math.min(best, once + (land > hi ? down[land - base] : 0));
        }
        return best;
    }

    private double fresh(int k) {
        if (Double.isNaN(fresh[k])) {
            fresh[k] = landing(k, 0);
        }
        return fresh[k];
    }

    /**
     * 一次落 {@code k} 个节点的落脚至少比水平底价多付多少:走出边沿加落下去的工夫减去疾跑一格。落差至少 {@code k} 减去落脚高度的
     * 小数部分 {@code fraction}。落一个节点还有向下挖(只付落下去的工夫,挖的钱在埋深里),规格许挖时取便宜的。
     */
    private double landing(int k, double fraction) {
        double landing = Kinematics.fallLandingTicks(body, k - fraction);
        double straight = Math.max(0, Kinematics.walkOffEdgeTicks(body) + landing - sprint);
        return k == 1 && model.spec().dig() ? Math.min(straight, landing) : straight;
    }

    /**
     * 快照里每一层有什么落脚:顶面的小数部分、落进去不摔的方块(液体、可攀爬的……)与它们头顶空着的高度。问到哪一层才读哪一层:按区段高度(16 层)一带一带读,
     * 一带里的调色板只问一遍。第 {@code y} 层的落脚来自这一层的矮方块和下面一层顶面超过一格的栅栏、墙({@link Footing} 找脚下的托也只看
     * 这两层),所以问第 {@code y} 层读它所在的一带,它是这一带最低的一层时再读下面那一带。
     */
    private static final class Levels {
        private final SearchView view;
        private final BodySnapshot body;
        private final int base;
        /** 节点在这一层、脚踩在矮方块上,脚在格里最高的小数部分。 */
        private final double[] fraction;
        /**
         * 这一层有落进去不摔的方块(液体、可攀爬的、落上去摔得轻的)时,落到其中最上面一格上的落差至多多少:那一格头顶往上连着空着
         * (不是整块实心)的格数,{@link #UNBOUNDED} 是扫到 {@link #SPAN} 格或读到没加载的列还空着;没有这样的方块为 0。
         */
        private final int[] reach;
        /** 这一带读过了。 */
        private final boolean[] bandRead;
        private final Map<BlockState, Info> known = new IdentityHashMap<>();

        private Levels(SearchView view, BodySnapshot body) {
            this.view = view;
            this.body = body;
            this.base = view.getMinBuildHeight();
            this.fraction = new double[view.getHeight()];
            this.reach = new int[view.getHeight()];
            this.bandRead = new boolean[(view.getHeight() + 15) >> 4];
        }

        double fraction(int y) {
            int i = y - base;
            if (i < 0 || i >= fraction.length) {
                return 0;
            }
            read(i);
            return fraction[i];
        }

        /**
         * 从高 {@code k} 层的地方落到第 {@code y} 层,能不能落进一格不摔的方块:这一层有这样的方块,头顶空着的格数不少于 {@code k}。
         * 身体落进去要穿过那一列上面的格,头顶压着整块实心(洞穴里的水潭、被屋顶盖住的梯井)的,从高处落不进去。
         */
        boolean catches(int y, int k) {
            int i = y - base;
            if (i < 0 || i >= reach.length) {
                return false;
            }
            read(i);
            return reach[i] >= k;
        }

        private final byte[] scan = new byte[SPAN];
        private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();

        /**
         * 落进 {@code (x, y, z)} 这一格不摔的方块,它头顶往上连着空着的格数。上面还有一格同样落进去不摔的:从上面落下来先落在那一格,
         * 这一格不是落脚,为 0;只有整块实心({@link CellFlags#WHOLE})才拦得住落下来的身体,别的(半砖、栅栏……)一律当空着数,
         * 宽松的一侧;读到没加载的列、或数满 {@link #SPAN} 格,{@link #UNBOUNDED}。
         */
        private int openAbove(int x, int y, int z) {
            BlockState above = view.loadedBlockState(cell.set(x, y + 1, z));
            if (above == null) {
                return UNBOUNDED;
            }
            if (known.computeIfAbsent(above, s -> Info.of(body, s)).bypass()) {
                return 0;
            }
            int blocking = CellFlags.WHOLE | CellFlags.UNLOADED;
            if ((view.cellFlags(x, y + 1, z, SPAN, scan, 0) & blocking) == 0) {
                return UNBOUNDED;
            }
            for (int k = 0; k < SPAN; k++) {
                int flags = scan[k] & 0xFF;
                if ((flags & blocking) != 0) {
                    return (flags & CellFlags.UNLOADED) != 0 ? UNBOUNDED : k;
                }
            }
            return UNBOUNDED;
        }

        /** 从第 {@code y} 层的节点升到上一层不用跳:这一层有顶面离下一格底不到迈步高度的落脚。 */
        boolean smoothRise(int y) {
            return 1 - fraction(y) <= body.stats().stepHeight() + Footing.EPSILON;
        }

        /** 第 {@code i} 层(从最低的一层数起)的落脚都读进来了。 */
        private void read(int i) {
            readBand(i >> 4);
            if ((i & 15) == 0 && i > 0) {
                readBand((i >> 4) - 1);
            }
        }

        private void readBand(int band) {
            if (bandRead[band]) {
                return;
            }
            bandRead[band] = true;
            int from = base + (band << 4);
            view.forEachBlock(from, from + 16, state -> known.computeIfAbsent(state, s -> Info.of(body, s)).interesting(), (state, x, y, z) -> {
                Info info = known.get(state);
                int i = y - base;
                if (info.bypass() && i >= 0 && i < reach.length) {
                    reach[i] = Math.max(reach[i], openAbove(x, y, z));
                }
                for (double top : info.tops()) {
                    int at = y + (int) Math.floor(top + Footing.EPSILON) - base;
                    if (at >= 0 && at < fraction.length) {
                        fraction[at] = Math.max(fraction[at], top - Math.floor(top + Footing.EPSILON));
                    }
                }
            });
        }
    }

    /** {@link Levels#reach} 里"扫满了还空着"。 */
    private static final int UNBOUNDED = Integer.MAX_VALUE;
    /** 头顶的空格最多往上数几格:数满了就当落多高都落得进来(宽松的一侧,估价仍是下界)。 */
    private static final int SPAN = 64;

    /** 比较"落上去摔多重"用的落差:大到任何减轻摔伤的方块都与石头分得出。 */
    private static final double SOFT_PROBE = 1000;

    /**
     * 一种方块状态:碰撞箱顶面的高度、能不能落进去不摔;以及值得逐格找出来的({@code interesting}):顶面有小数(不是整格也不是空),
     * 或落进去不摔(含落上去比石头摔得轻)。调色板里每种状态每个方块段都问一遍,所以开搜时算定。
     */
    private record Info(double[] tops, boolean bypass, boolean interesting) {

        static Info of(BodySnapshot body, BlockState state) {
            // 落上去比石头摔得轻的方块(摔伤的公式只在 BodySnapshot.fallDamage 一处):落差不受硬地上限约束
            boolean softens = body.fallDamage(SOFT_PROBE, state) < body.fallDamage(SOFT_PROBE, Blocks.STONE.defaultBlockState());
            boolean bypass = !state.getFluidState().isEmpty() || state.is(BlockTags.CLIMBABLE) || state.getBlock() instanceof TrapDoorBlock
                    || softens;
            double[] tops = Footing.tops(body.stats(), state);
            boolean interesting = bypass;
            for (double top : tops) {
                interesting |= top - Math.floor(top + Footing.EPSILON) > Footing.EPSILON;
            }
            return new Info(tops, bypass, interesting);
        }
    }
}
