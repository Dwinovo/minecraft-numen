package com.dwinovo.numen.pathing.plan;

import java.util.Arrays;
import java.util.List;

import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.CellFlags;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 走法的快判断用的一节点的上下文:搜索每展开一个节点,先在这里读好它四周一圈的标志字({@link CellFlags}),各走法
 * ({@link Move#quick})只读标志字、按位判断、查表得价,不建 {@link Maneuver}、不叠草稿、不算碰撞箱、不问许可。一步有三种答复:
 * <ul>
 *   <li>{@link #REJECT}:这一步的前提必然不成立,精确的 {@link Move#premise} 一定交出 {@link Premise.Fails};</li>
 *   <li>{@link #HOLDS}:前提必然成立,落点与代价在 {@link #toX}、{@link #cost} 等处,与精确判断得出的一模一样;
 *       只有"平地上站着不改地形"的步子({@link #plain})会这样答;</li>
 *   <li>{@link #LIKELY}:可能成立,{@link #cost} 是乐观的代价——不高于精确判断成立时的代价,落点按标志字定(落点随地形而定的下落、
 *       跑酷取最近的那个)。要挖要放、四周有半砖、楼梯、水、梯子这类标志字说不清的格,都这样答;搜索把它当未核的候选放进开放集,
 *       取出来时才做精确判断(Lazy Weighted A*,Cohen、Phillips、Likhachev 2014)。</li>
 * </ul>
 * 答 {@link #REJECT} 与 {@link #HOLDS} 的条件都由精确的几何({@link com.dwinovo.numen.pathing.world.Stepping}、
 * {@code Footing}、{@code Clearance}、{@link Contact})在"四周全是 {@link CellFlags#PASS} 与 {@link CellFlags#WHOLE}"时的结果推出;
 * {@link #LIKELY} 的代价由同一份公式在最省的情形下给出:步速取各种步态里最快的、脚下的步速系数取 1、不加按位置的价与罚分,
 * 标志字断定要挖的整块按 {@link CostModel#digFloor}、要垫的一块按 {@link CostModel#placeFloor} 计。单测在随机世界上逐节点逐走法逐方向
 * 与精确判断核对:答"不成立"的精确一定不成立,答"可能"的代价不高于精确的。
 *
 * <p>节点面对的世界是快照加上走到这个节点的那一步做过的改动(挖开的格、垫下的块、开关过的门):改过的那几格的标志字由改完的
 * 世界上的方块状态推出({@link EditedView}),盖在快照读来的标志字上。
 *
 * <p>一次搜索一份,只在跑这次搜索的线程上用;结果字段每次判断都被覆盖。
 */
public final class Quick {

    public static final int REJECT = 0;
    public static final int HOLDS = 1;
    public static final int LIKELY = 2;

    /** 读标志字的范围:节点所在的列往四周各两列(四个角不读),往下两格、往上到头顶上一格。 */
    private static final int RADIUS = 2;
    private static final int SIDE = 2 * RADIUS + 1;
    private static final int BELOW = 2;
    /** 往下找落点最多看这么多格,再深的答"可能"。 */
    private static final int SCAN = 64;

    private final CostModel model;
    private final WorldView view;
    private final boolean usable;
    /** 站直的身体比脚所在那一格再高占几格。 */
    private final int top;
    private final int rows;
    private final boolean positional;
    private final boolean mayPlace;
    private final boolean mayDig;
    private final boolean fallingExcluded;
    private final boolean standingBanned;
    private final byte[] window;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final Stance[] ground;
    /** 平地上走一格的刻数:疾跑或平走,脚下的步速系数是 1。 */
    private final double pace;
    private final double jumpPenalty;
    /** 起跳落到比起跳时高一格、同一高度的平面上要几刻。 */
    private final double stepJump;
    private final double levelJump;
    /** 走出边沿的工夫,与落下 {@code depth} 格再落地的工夫(用到才算,算过就记下;还没算为 NaN)。 */
    private final double walkOff;
    private final double[] landing = new double[SCAN + 2];
    /** 各种步态里平地走一格最快的刻数:疾跑(跑不动时平走)与涉水取快的,脚下的步速系数只会更慢。 */
    private final double fastest;
    /** 先在原地改地形再迈步的一步每走一格至少几刻:这样的步跑不起来({@link Maneuver#runnable}),至少是平走。 */
    private final double working;
    /** 放一块、挖一格之外加的那些都不少于 0:放一块至少 {@link CostModel#placeFloor}。 */
    private final double placeFloor;
    /** 走出边沿往下落一个节点、两个节点以上至少要几刻:落差分别大于 0 格与 1 格。 */
    private final double descendFloor;
    private final double fallFloor;

    // ---- 此刻的节点 ----
    private int x;
    private int y;
    private int z;
    private Stance stance;
    /** 节点面对的世界;没改过地形就是快照本身。 */
    private WorldView reading;
    private boolean plain;
    private boolean calm;
    private int own;
    /** 走到这个节点那一步改过的格与改完之后它们的标志字。 */
    private long[] changedAt = new long[4];
    private int[] changedFlags = new int[4];
    private int changes;

    // ---- 这一步新进入的格(只在有按位置的禁令与加价时才记) ----
    private long[] cells = new long[16];
    private int count;

    // ---- 这一步的结果 ----
    private int toX;
    private int toY;
    private int toZ;
    private double cost;
    private double ticks;
    private boolean guessed;

    /** 为一次搜索备好:{@code view} 是搜索读的快照,{@code model} 是搜索用的(已并进目标格保护的)成本模型。 */
    public Quick(CostModel model, WorldView view) {
        BodyStats body = model.body().stats();
        this.model = model;
        this.view = view;
        this.top = Clearance.topCell(body, 0.0);
        this.rows = top + BELOW + 2;
        this.window = new byte[SIDE * SIDE * rows];
        this.positional = model.positional();
        this.mayPlace = model.mayPlace();
        this.mayDig = model.spec().dig();
        this.fallingExcluded = model.excludes(Semantics.bit(Semantics.Kind.FALLING));
        this.standingBanned = !model.spec().bans().standingOn().isEmpty();
        this.pace = Strides.pace(model, false, false, true, 1.0);
        this.jumpPenalty = model.spec().jumpPenalty();
        this.stepJump = Strides.jump(model, 1.0);
        this.levelJump = Strides.jump(model, 0.0);
        this.walkOff = Kinematics.walkOffEdgeTicks(body);
        Arrays.fill(landing, Double.NaN);
        this.fastest = Math.min(pace, model.waterStep());
        this.working = Strides.pace(model, false, false, false, 1.0);
        this.placeFloor = model.placeFloor();
        this.descendFloor = walkOff + Strides.landing(model, 0.0);
        this.fallFloor = walkOff + Strides.landing(model, 1.0);
        this.ground = new Stance[view.getHeight() + 2];
        this.usable = usable(body);
    }

    /**
     * 快判断推导时假设的身体:细雪托不住(托得住的身体踩细雪不算伤身),宽度不到一格,站直了占两格高(高过一格、不过 1.8),
     * 眼睛在头两格之内,迈步比一格低、起跳比一格高比两格低。再不一样的身体只答"不成立"与"可能"。
     */
    private static boolean usable(BodyStats body) {
        double jump = Kinematics.jumpHeight(body, 1.0);
        return !body.walksOnPowderSnow() && body.width() > 0 && body.width() <= 0.9 && body.height() > 1.0 + 1.0E-3
                && body.height() <= 1.8 + 1.0E-9 && body.eyeHeight() > 0 && body.eyeHeight() < 2.0 && body.stepHeight() >= 0
                && body.stepHeight() < 0.999 && jump >= 1.001 && jump < 1.9;
    }

    // ==================== 节点 ====================

    /**
     * 读好节点 {@code (x, y, z)} 四周的标志字。{@code here} 是节点面对的世界(快照,或快照加上走到这里那一步的改动
     * {@code arrived}),{@code arrived} 里改了世界的几件由它们改完之后的方块状态盖在标志字上。
     * {@link #plain}:节点是平地上稳稳站着的(脚正好在这一格的底、踩着整块),四周也都读到了。
     */
    public void begin(int x, int y, int z, Stance stance, WorldView here, List<Edit> arrived) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.stance = stance;
        this.plain = false;
        this.calm = false;
        changes = 0;
        if (arrived.isEmpty()) {
            this.reading = view;
        } else {
            this.reading = here;
            for (Edit edit : arrived) {
                if (edit instanceof Edit.Catch) {
                    // 倒下的水在这一步里就收回了,世界照旧
                    continue;
                }
                if (changes == changedAt.length) {
                    changedAt = Arrays.copyOf(changedAt, changes * 2);
                    changedFlags = Arrays.copyOf(changedFlags, changes * 2);
                }
                changedAt[changes] = edit.pos().asLong();
                changedFlags[changes] = CellFlags.of(here.getBlockState(edit.pos()));
                changes++;
            }
        }
        this.own = flags(x, y, z);
        if (!usable || stance.kind() != Stance.Kind.GROUND || stance.feetY() != y || stance.supportY() != y - 1) {
            return;
        }
        int seen = 0;
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                if (Math.abs(dx) == RADIUS && Math.abs(dz) == RADIUS) {
                    continue;
                }
                seen |= view.cellFlags(x + dx, y - BELOW, z + dz, rows, window, ((dz + RADIUS) * SIDE + dx + RADIUS) * rows);
                if ((seen & CellFlags.UNLOADED) != 0) {
                    return;
                }
            }
        }
        for (int i = 0; i < changes; i++) {
            int dx = BlockPos.getX(changedAt[i]) - x;
            int dy = BlockPos.getY(changedAt[i]) - y;
            int dz = BlockPos.getZ(changedAt[i]) - z;
            if (Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS && !(Math.abs(dx) == RADIUS && Math.abs(dz) == RADIUS)
                    && dy >= -BELOW && dy < rows - BELOW) {
                window[((dz + RADIUS) * SIDE + dx + RADIUS) * rows + dy + BELOW] = (byte) changedFlags[i];
            }
        }
        if (!whole(cell(0, -1, 0))) {
            return;
        }
        for (int dy = 0; dy <= top; dy++) {
            if (!pass(cell(0, dy, 0))) {
                return;
            }
        }
        if (changes > 0) {
            seen = 0;
            for (byte flags : window) {
                seen |= flags & 0xFF;
            }
        }
        this.plain = true;
        this.calm = (seen & CellFlags.HARMFUL) == 0;
    }

    public Stance stance() {
        return stance;
    }

    /** 节点是平地上稳稳站着、四周读全了:各走法才敢按标志字断定。 */
    boolean plain() {
        return plain;
    }

    /** 节点所在那一格的标志字。 */
    int own() {
        return own;
    }

    /** 读好的一圈里没有一格伤身:从身体新进入的格、脚下那一格往外数,挨着的格都不会烫。 */
    boolean calm() {
        return calm;
    }

    int top() {
        return top;
    }

    boolean mayPlace() {
        return mayPlace;
    }

    boolean mayDig() {
        return mayDig;
    }

    CostModel model() {
        return model;
    }

    WorldView view() {
        return view;
    }

    int x() {
        return x;
    }

    int y() {
        return y;
    }

    int z() {
        return z;
    }

    double pace() {
        return pace;
    }

    double jumpPenalty() {
        return jumpPenalty;
    }

    /** 起跳落在比起跳时高一格的平面上的工夫。 */
    double stepJump() {
        return stepJump;
    }

    /** 起跳落在同一高度的平面上的工夫。 */
    double levelJump() {
        return levelJump;
    }

    double walkOff() {
        return walkOff;
    }

    /** 平地走一格最快的刻数:疾跑(跑不动时平走)与涉水取快的。 */
    double fastest() {
        return fastest;
    }

    /** 要挖要放的一步、不泡水时每走一格至少几刻:跑不起来,至少是平走。 */
    double working() {
        return working;
    }

    /** 放一块至少多少钱。 */
    double placeFloor() {
        return placeFloor;
    }

    /** 走出边沿往下落一个节点至少几刻。 */
    double descendFloor() {
        return descendFloor;
    }

    /** 走出边沿往下落两个节点以上至少几刻。 */
    double fallFloor() {
        return fallFloor;
    }

    /**
     * 挖掉相对节点 {@code (dx, dy, dz)} 那一列从 {@code fromDy} 到 {@code toDy}(含)里整块的格至少多少钱({@link CostModel#digFloor},
     * 节点面对的世界里的方块);有一格挖不动为无穷大。
     */
    double digFloor(int dx, int fromDy, int toDy, int dz) {
        double sum = 0;
        for (int dy = fromDy; dy <= toDy; dy++) {
            if (whole(cell(dx, dy, dz))) {
                sum += model.digFloor(state(dx, dy, dz));
            }
        }
        return sum;
    }

    /** 落下 {@code depth} 格再落地的工夫({@link Strides#landing})。 */
    double landing(int depth) {
        double ticks = landing[depth];
        if (Double.isNaN(ticks)) {
            ticks = Strides.landing(model, depth);
            landing[depth] = ticks;
        }
        return ticks;
    }

    /** 往下找落点的下限:建筑高度的底。 */
    int floorY() {
        return view.getMinBuildHeight();
    }

    int scan() {
        return SCAN;
    }

    // ==================== 读标志字 ====================

    /** 节点相对位置 {@code (dx, dy, dz)} 那一格的标志字:{@code dx}、{@code dz} 在两列以内、{@code dy} 从 -2 到头顶上一格。 */
    int cell(int dx, int dy, int dz) {
        return window[((dz + RADIUS) * SIDE + dx + RADIUS) * rows + dy + BELOW] & 0xFF;
    }

    /**
     * 节点相对位置 {@code (dx, dy, dz)} 那一格的标志字,现读:读好的一圈之外(往下更深)的格,或节点不平、那一圈没读时。
     * 没加载的列带 {@link CellFlags#UNLOADED}。
     */
    int flagsAt(int dx, int dy, int dz) {
        return flags(x + dx, y + dy, z + dz);
    }

    /**
     * 节点相对 {@code (dx, dz)} 那一列和它四面紧挨着的列都加载着:精确判断看一步的落点时读到的列不出这几列,节点不平、那一圈没读时,
     * 快判断凭现读的标志字断定"不成立"之前先确认这一点(断定了的步子,精确判断不能读到没加载的列)。
     */
    boolean loadedAround(int dx, int dz) {
        return ((flagsAt(dx, 0, dz) | flagsAt(dx + 1, 0, dz) | flagsAt(dx - 1, 0, dz) | flagsAt(dx, 0, dz + 1) | flagsAt(dx, 0, dz - 1))
                & CellFlags.UNLOADED) == 0;
    }

    /** 身体站在节点相对 {@code (dx, dy, dz)} 那个节点时脚的高度({@link Footing#height},节点面对的世界里的);托不住为 NaN。 */
    double footing(int dx, int dy, int dz) {
        return Footing.height(reading, model.body().stats(), x + dx, y + dy, z + dz);
    }

    /** 节点面对的世界里 {@code (x, y, z)} 那一格的标志字:快照读来的,被这一步改过的格换成改完之后的。 */
    private int flags(int x, int y, int z) {
        if (changes > 0) {
            long at = BlockPos.asLong(x, y, z);
            for (int i = changes - 1; i >= 0; i--) {
                if (changedAt[i] == at) {
                    return changedFlags[i];
                }
            }
        }
        return view.cellFlags(x, y, z);
    }

    /**
     * 落点那一列 {@code (dx, dz)} 往下到 {@code fromDy}(含)、往上到 {@code toDy}(含)这些层,四面紧挨着的格都不伤身:
     * 读好的一圈只到往下两格,更深的落差要另看。
     */
    boolean calmBelow(int dx, int dz, int fromDy, int toDy) {
        for (int dy = fromDy; dy <= toDy; dy++) {
            if (((flagsAt(dx + 1, dy, dz) | flagsAt(dx - 1, dy, dz) | flagsAt(dx, dy, dz + 1) | flagsAt(dx, dy, dz - 1))
                    & CellFlags.HARMFUL) != 0) {
                return false;
            }
        }
        return true;
    }

    static boolean pass(int flags) {
        return (flags & CellFlags.PASS) != 0;
    }

    static boolean whole(int flags) {
        return (flags & CellFlags.WHOLE) != 0;
    }

    /** 全空或整块:几何上没有别的可说。 */
    static boolean simple(int flags) {
        return (flags & (CellFlags.PASS | CellFlags.WHOLE)) != 0;
    }

    /** 相对节点 {@code (dx, dy, dz)} 那一格的方块状态(节点面对的世界里的)。 */
    BlockState state(int dx, int dy, int dz) {
        return reading.getBlockState(probe.set(x + dx, y + dy, z + dz));
    }

    // ==================== 站着的落脚 ====================

    /** 站在 {@code y} 这一格的底上、踩着整块的站姿(同 {@link Stance#at} 对这样的格交出的):每个高度共用一个。 */
    public Stance ground(int y) {
        int i = y - view.getMinBuildHeight();
        if (i < 0 || i >= ground.length) {
            return new Stance(Stance.Kind.GROUND, y, y - 1);
        }
        Stance cached = ground[i];
        if (cached == null) {
            cached = new Stance(Stance.Kind.GROUND, y, y - 1);
            ground[i] = cached;
        }
        return cached;
    }

    // ==================== 这一步的记账 ====================

    /** 开始记这一步身体新进入的格。 */
    void entering() {
        count = 0;
    }

    /** 身体这一步新进入一格:相对节点的位置。没有按位置的禁令与加价就没什么可查,不记。 */
    void enter(int dx, int dy, int dz) {
        if (!positional) {
            return;
        }
        if (count == cells.length) {
            cells = Arrays.copyOf(cells, count * 2);
        }
        cells[count++] = BlockPos.asLong(x + dx, y + dy, z + dz);
    }

    /** 记下的格里有被按位置禁止穿过的。 */
    boolean forbidsPassing() {
        for (int i = 0; i < count; i++) {
            if (model.forbids(Use.PASS, cells[i])) {
                return true;
            }
        }
        return false;
    }

    /** 这一格(标志字是 {@code flags},除"会塌"之外没有语义种类)这条路线排除。 */
    boolean excluded(int flags) {
        return fallingExcluded && (flags & CellFlags.FALLING) != 0;
    }

    /**
     * 脚踩的格 {@code (dx, dy, dz)}(相对节点,{@code flags} 是它的标志字)这条路线踩不踩得:被排除的种类、按位置禁止站上、
     * 按方块种类禁止站上。踩得返回 true。
     */
    boolean standsOn(int dx, int dy, int dz, int flags) {
        if (excluded(flags)) {
            return false;
        }
        if (positional && model.forbids(Use.STAND, BlockPos.asLong(x + dx, y + dy, z + dz))) {
            return false;
        }
        return !standingBanned || !model.spec().bans().standingOn().contains(state(dx, dy, dz).getBlock());
    }

    /** 身体新进入的格与脚下那一格 {@code (dx, dy, dz)} 的按位置加价,没有按位置的加价是 0。 */
    double overhead(int dx, int dy, int dz) {
        if (!positional) {
            return 0.0;
        }
        return model.passing(0.0, cells, count, 0, true, BlockPos.asLong(x + dx, y + dy, z + dz), false);
    }

    /** 这一步前提成立:落到 {@code (toX, toY, toZ)},价钱 {@code cost},真要的刻数 {@code ticks}。 */
    int holds(int toX, int toY, int toZ, double cost, double ticks) {
        this.toX = toX;
        this.toY = toY;
        this.toZ = toZ;
        this.cost = cost;
        this.ticks = ticks;
        this.guessed = false;
        return HOLDS;
    }

    /** 这一步可能成立:成立的话落到节点相对 {@code (dx, dy, dz)} 处,代价至少 {@code cost}。 */
    int likely(int dx, int dy, int dz, double cost) {
        return likely(dx, dy, dz, cost, false);
    }

    /**
     * 这一步可能成立,落点随地形而定、标志字定不下来(往下落多深、跳过几列):{@code (dx, dy, dz)} 是最近的那个可能的落点,
     * 代价至少 {@code cost}——对哪个落点都不高估。
     */
    int likelyNear(int dx, int dy, int dz, double cost) {
        return likely(dx, dy, dz, cost, true);
    }

    private int likely(int dx, int dy, int dz, double cost, boolean guessed) {
        this.toX = x + dx;
        this.toY = y + dy;
        this.toZ = z + dz;
        this.cost = cost;
        this.ticks = Double.NaN;
        this.guessed = guessed;
        return LIKELY;
    }

    /** 答"可能"时,落点是猜的最近的那个:精确判断可能落在更远处。 */
    public boolean guessed() {
        return guessed;
    }

    public int toX() {
        return toX;
    }

    public int toY() {
        return toY;
    }

    public int toZ() {
        return toZ;
    }

    public double cost() {
        return cost;
    }

    public double ticks() {
        return ticks;
    }
}
