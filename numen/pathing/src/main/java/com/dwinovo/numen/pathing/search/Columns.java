package com.dwinovo.numen.pathing.search;

import java.util.Arrays;
import java.util.function.Predicate;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.world.CellFlags;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Kinematics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 估价的水平项:从节点走到目标,水平上至少要付多少刻。按列的放宽图上的最短路,从目标反着做的多源 Dijkstra
 * (Factorio 的抽象寻路从终点倒着搜、结果当底层 A* 的估价 [FFF #317];Nav2 NavFn 在代价地图上从终点做 Dijkstra 得到导航函数;
 * 抽象启发式 [Holte 等 1996])。
 *
 * <p><b>图</b>:节点是快照里的列 {@code (x, z)},相邻八个方向连边,斜着一步长 √2。一条边的价钱是走进那一列的水平价,取该列上任何走法的最低:
 * <ul>
 *   <li>陆地:疾跑一格({@link Kinematics#pace})。身体的步速不会比它更快(脚下的方块只会让步速系数更小),落进一列的下落、爬上去、
 *       跑酷落点的水平部分也不比它短;</li>
 *   <li>水:这一列上<b>没有任何一处站得住的格</b>(整列只有水、实心的水底和水面上的空气:湖、海)时,从同样是这样的一列走进来是游或涉水,
 *       价是 {@link CostModel#waterStep} 乘步长再加涉水罚分(与 {@code Swim}、涉水的 {@code Walk} 一样);规格许放、身上有料时取搭桥
 *       (疾跑加放一块的最低价 {@link CostModel#placeFloor})较低的。从别的列(岸上)走进来可以是下落或平走进去,仍是疾跑价——
 *       水里第一步不比岸上贵,才不会高估;</li>
 *   <li>规格许跑酷时每一列都按疾跑价:一次跳越过的空隙可以是水。</li>
 * </ul>
 * 站得住的格:身体放得下(脚和头的格都不是整块实心)、脚下一格托得住(整块实心或别的不空不含流体的形状:睡莲、半砖、浮冰都算;
 * 含水的半砖、台阶、活板门露在水面上的那一格也算),或是可攀爬的格、岩浆。一列上有一处这样的格,就按陆地算(宽松的一侧:下面的洞穴也算,
 * 下界仍成立)。挖开的格另由 {@link Burial} 算,升降由 {@link Vertical} 算,这里只管水平。
 *
 * <p><b>种子</b>:终点格所在的列(价是 {@link Goal#endCost},多数目标为 0);快照边界上的列({@link SearchView#forEachBoundaryColumn}),各带自己到目标的直线疾跑下界
 * ({@link Goal#estimate})——目标在快照之外时路线要从边界出去。没有固定终点格的目标({@link Goal#endCells} 为 null)没有可反着做的图:
 * 每一列都是自己的种子,值就是直线下界,Dijkstra 改不了它,所以不跑,估价直接取 {@link Goal#estimate}。
 *
 * <p><b>惰性</b>:不一次做完整张图。问到哪一列才往外定到哪一列({@link #floor}),起点那一列是第一个被问的;搜索展开到哪、
 * 估价读到哪,图就算到哪。定列的先后按"已付的加这一列到起点的八方向距离"排(A* 的做法,距离每条边都不比边价贵,是一致的下界),
 * 所以空旷的地面只定起点到目标之间窄窄一条,不铺满以目标为心的整个圆;被问到的列不在这一条里,就接着往外定到它。
 * 定下的列的值是精确的最短路,所以一致;定下一列读它的八个邻居,
 * 判种类时读整列的标志字,这些活量由 {@link #settled} 记给搜索的时钟({@link SearchClock.Timer#precomputed})。
 * 只能在跑这次搜索的线程上用。
 */
final class Columns {

    private static final Predicate<BlockState> FLUID = state -> !state.getFluidState().isEmpty();
    private static final double DIAGONAL = Math.sqrt(2);
    private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Goal goal;
    private final Kinematics.Pace pace;
    /** 没有固定终点格:不跑图,估价取目标自己的直线下界。 */
    private final boolean straight;
    private final SearchView view;
    private final BlockPos start;
    private final int minY;
    private final int height;
    /** 身体站着占几格高。 */
    private final int tall;
    /** 疾跑走进一格;水里走进一格的价(正方向,不含涉水罚分);涉水罚分;搭桥时放一块的最低价(不能搭为无穷)。 */
    private final double sprint;
    private final double swim;
    private final double wade;
    private final double bridge;
    private final boolean parkour;
    private final Pages pages;
    private final CostQueue queue = new CostQueue();
    private final byte[] flags;
    private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
    /** 已定下的列数,与最近一次定下的价钱(队列空了、还没定下的列至少是它)。 */
    private int settled;
    private double reached;

    private Columns(SearchView view, CostModel model, Goal goal, BlockPos start, Kinematics.Pace pace, boolean straight) {
        this.view = view;
        this.goal = goal;
        this.start = start;
        this.pace = pace;
        this.straight = straight;
        this.minY = view.getMinBuildHeight();
        this.height = view.getHeight();
        this.tall = Clearance.topCell(model.body().stats(), 0) + 1;
        this.sprint = pace.sprint();
        this.swim = model.waterStep();
        this.wade = model.spec().wadePenalty();
        this.bridge = model.spec().place() && model.body().mayEdit() && model.placing().isPresent()
                ? model.placeFloor() : Double.POSITIVE_INFINITY;
        this.parkour = model.spec().parkour();
        this.flags = new byte[17 + tall];
        this.pages = new Pages(view);
    }

    /** 这次搜索的水平项:在 {@code view} 的列上从 {@code goal} 的终点往外算,问到哪算到哪。 */
    static Columns of(SearchView view, CostModel model, Goal goal, BlockPos start, Kinematics.Pace pace) {
        LongSet ends = goal.endCells();
        Columns columns = new Columns(view, model, goal, start, pace, ends == null);
        if (ends != null) {
            columns.seed(ends);
        }
        return columns;
    }

    /** 脚在节点 {@code (x, y, z)} 的身体走进目标水平上至少还要多少刻。 */
    double floor(int x, int y, int z) {
        if (straight) {
            return goal.estimate(x, y, z, pace);
        }
        Page page = pages.at(x, z);
        int i = Pages.index(x, z);
        while (!page.done[i] && !queue.isEmpty()) {
            settleNext();
        }
        // 队列空了还没定下的列不在图里(没加载的):至少是最后定下的价钱
        return page.done[i] ? page.best[i] : reached;
    }

    /** 已经定下多少列:搜索的时钟按它折算预计算的活量。 */
    int settled() {
        return settled;
    }

    private void seed(LongSet ends) {
        for (long end : ends) {
            int x = BlockPos.getX(end);
            int z = BlockPos.getZ(end);
            if (view.isLoaded(x, z)) {
                offer(x, z, goal.endCost(end));
            }
        }
        int seedY = goal.heights() != null ? goal.heights().lo() : start.getY();
        view.forEachBoundaryColumn((x, z) -> offer(x, z, goal.estimate(x, seedY, z, pace)));
    }

    private void offer(int x, int z, double cost) {
        Page page = pages.at(x, z);
        int i = Pages.index(x, z);
        if (cost < page.best[i] && !page.done[i]) {
            page.best[i] = cost;
            queue.push(cost + toStart(x, z), 0, BlockPos.asLong(x, 0, z));
        }
    }

    /** 这一列到起点那一列的八方向距离乘疾跑一格:每条边至少这么贵,所以是一致的下界,Dijkstra 按它引着往起点的方向定。 */
    private double toStart(int x, int z) {
        int dx = Math.abs(x - start.getX());
        int dz = Math.abs(z - start.getZ());
        return sprint * (Math.min(dx, dz) * DIAGONAL + Math.abs(dx - dz));
    }

    /** 定下队列里"已付的加到起点的距离"最小的一列,往回松弛它的八个邻居。 */
    private void settleNext() {
        double key = queue.peekCost();
        long column = queue.pop();
        int x = BlockPos.getX(column);
        int z = BlockPos.getZ(column);
        Page page = pages.at(x, z);
        int i = Pages.index(x, z);
        double cost = page.best[i];
        if (page.done[i] || key > cost + toStart(x, z)) {
            return;
        }
        page.done[i] = true;
        settled++;
        reached = cost;
        boolean water = water(x, z);
        for (int[] d : AROUND) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!pages.at(nx, nz).loaded) {
                continue;
            }
            double length = d[0] != 0 && d[1] != 0 ? DIAGONAL : 1;
            offer(nx, nz, cost + (water && water(nx, nz) ? Math.min(swim * length + wade, sprint * length + bridge) : sprint * length));
        }
    }

    /** 这一列是水列:没有一处站得住的格,却有水。规格许跑酷时没有水列(一跳可以越过水)。 */
    private boolean water(int x, int z) {
        if (parkour) {
            return false;
        }
        Page page = pages.at(x, z);
        int i = Pages.index(x, z);
        byte known = page.water[i];
        if (known < 0) {
            known = (byte) (waterColumn(x, z) ? 1 : 0);
            page.water[i] = known;
        }
        return known == 1;
    }

    /**
     * 整列从下往上按区段读标志字({@link CellFlags}):区段的调色板里没有流体的列没有水,不读;两个区段都是空气的不读(空气里没有托着脚的东西)。
     * 有站得住的格就不是水列,没有站得住的格又没见过水,也不是水列(整块实心的列、空列:按陆地算,宽松)。
     */
    private boolean waterColumn(int x, int z) {
        if (!mayHoldWater(x, z)) {
            return false;
        }
        boolean water = false;
        boolean previousAir = true;
        for (int y0 = minY; y0 < minY + height; y0 += 16) {
            boolean air = view.airSection(x, y0, z);
            if (air && previousAir) {
                continue;
            }
            previousAir = air;
            view.cellFlags(x, y0 - 1, z, flags.length, flags, 0);
            for (int i = 1; i <= 16; i++) {
                int f = flags[i] & 0xFF;
                if ((f & CellFlags.UNLOADED) != 0 || (f & CellFlags.FLUID) != 0 && (f & CellFlags.HARMFUL) != 0) {
                    return false;
                }
                if ((f & CellFlags.FLUID) != 0) {
                    if ((flags[i + 1] & CellFlags.FLUID) == 0 && !(view.getBlockState(cell.set(x, y0 - 1 + i, z)).getBlock() instanceof LiquidBlock)
                            && headroom(i + 1)) {
                        // 水面上的这一格不是纯水而是含着水的方块(半砖、台阶、活板门):脚能踩上去,上面有空就是站得住
                        return false;
                    }
                    water = true;
                } else if ((f & CellFlags.WHOLE) == 0 && ((f & CellFlags.CLIMBISH) != 0 || standable(i))) {
                    return false;
                }
            }
        }
        return water;
    }

    /** 这一列所在的区段里,有哪一段的调色板里有含着流体的状态:一段都没有,这一列没有水。 */
    private boolean mayHoldWater(int x, int z) {
        for (int y0 = minY; y0 < minY + height; y0 += 16) {
            if (view.sectionMayHold(x, y0, z, FLUID)) {
                return true;
            }
        }
        return false;
    }

    /** 脚在 {@code flags} 里第 {@code i} 格:身体放得下({@link #headroom})、脚下托得住。 */
    private boolean standable(int i) {
        if (!headroom(i)) {
            return false;
        }
        int below = flags[i - 1] & 0xFF;
        return (below & CellFlags.WHOLE) != 0 || (below & (CellFlags.PASS | CellFlags.FLUID)) == 0;
    }

    /** 脚在 {@code flags} 里第 {@code i} 格时,身体占的格(脚的格起往上 {@link #tall} 格)都不是整块实心。 */
    private boolean headroom(int i) {
        for (int t = 0; t < tall; t++) {
            if ((flags[i + t] & CellFlags.WHOLE) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 按 16×16 列一页存每列的状态:查一列是一次页表查找(连续问同一页时是缓存)加数组下标,比一列一个哈希项省得多。 */
    private static final class Pages {

        private final Long2ObjectOpenHashMap<Page> pages = new Long2ObjectOpenHashMap<>();
        private final SearchView view;
        private Page last;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;

        Pages(SearchView view) {
            this.view = view;
        }

        Page at(int x, int z) {
            int px = x >> 4;
            int pz = z >> 4;
            if (last == null || px != lastX || pz != lastZ) {
                last = pages.computeIfAbsent(BlockPos.asLong(px, 0, pz), k -> new Page(view.isLoaded(x, z)));
                lastX = px;
                lastZ = pz;
            }
            return last;
        }

        static int index(int x, int z) {
            return (z & 15) << 4 | (x & 15);
        }
    }

    /** 一页:这一页的区块在不在快照里、每列到目标的最低价(没算过为无穷)、定下没有、是不是水列(没判过为 -1)。 */
    private static final class Page {
        final boolean loaded;
        final double[] best = new double[256];
        final boolean[] done = new boolean[256];
        final byte[] water = new byte[256];

        Page(boolean loaded) {
            this.loaded = loaded;
            Arrays.fill(best, Double.POSITIVE_INFINITY);
            Arrays.fill(water, (byte) -1);
        }
    }
}
