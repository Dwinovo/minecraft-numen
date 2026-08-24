package com.dwinovo.numen.core.task.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Comparator;

/**
 * 施工顺序与节奏的<b>唯一定义</b>:先后(比较器与它的两个判据)与快慢
 * (速率公式与时长估计)。全是无状态纯函数——派发方(BuildTool 的时限
 * 公式)、测试与施工任务共用同一份,不许各拍各的。
 */
public final class BuildOrder {

    private BuildOrder() {}

    /**
     * 施工节奏:由<b>目标总时长</b>反推速率,再夹在上下限之间。
     *
     * <p>固定速率两头不讨好:定成"每秒两格"的手感,小屋四分钟正好,五千多格的
     * 大屋要盖四十九分钟;定快了小屋一眨眼就没了。改成先给一个总时长目标,速率
     * 由格数除出来——不管盖多大,时长都可预期,而且都有戏看。
     */
    static final double SURVIVAL_MIN_RATE = 2.0 / 20.0;    // 每秒 2 格,慢的那一头
    static final double SURVIVAL_TARGET_TICKS = 12 * 60 * 20;   // 再大也不超过 12 分钟
    static final double FREE_MAX_RATE = 100.0 / 20.0;      // 创造快,但不瞬移
    static final double FREE_TARGET_TICKS = 25 * 20;       // 再小也演满 25 秒

    /** 施工顺序的唯一定义(公开是为了让测试直接钉住它,而不是靠副作用间接猜)。 */
    public static final Comparator<BuildTaskRecord.Target> BUILD_ORDER = Comparator
            .comparingInt((BuildTaskRecord.Target t) -> needsSupport(t.desiredState()) ? 1 : 0)
            .thenComparingInt(t -> t.pos().getY())
            .thenComparingInt(BuildOrder::stage)
            .thenComparingInt(t -> t.pos().getZ())
            .thenComparingInt(t -> (t.pos().getZ() & 1) == 0 ? t.pos().getX() : -t.pos().getX());

    /**
     * 这一格立不立得住:要依托别的方块的算<b>贴附件</b>,推到第二趟。
     *
     * <p>按方块类型判,不按"能不能存活"现场试——现场试要有支撑才知道答案,而主趟
     * 正是支撑还没长出来的时候。类型是封闭集合,一次列完;"能不能存活"是开放的,
     * 每来一个新方块就得被咬一次。
     *
     * <p>花草(BushBlock)、花盆、雪层这三类是特意加进来的:我们是<b>分遍</b>推进的
     * 慢速施工,一格放不下去要等到下一遍,来回几次就是几十秒;宁可一开始就把它们
     * 放到最后一趟。地毯用 CarpetBlock 而不是只管羊毛地毯,苔藓地毯同样要依托。
     */
    public static boolean needsSupport(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.HANGING)) {
            return true;   // 挂着的灯笼与告示牌
        }
        var b = state.getBlock();
        return b instanceof net.minecraft.world.level.block.LadderBlock
                || b instanceof net.minecraft.world.level.block.BaseTorchBlock
                || b instanceof net.minecraft.world.level.block.SignBlock
                || b instanceof net.minecraft.world.level.block.BasePressurePlateBlock
                || b instanceof net.minecraft.world.level.block.BaseRailBlock
                || b instanceof net.minecraft.world.level.block.DiodeBlock
                || b instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || b instanceof net.minecraft.world.level.block.CarpetBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock
                || b instanceof net.minecraft.world.level.block.SnowLayerBlock
                // 按钮、拉杆这类贴面件;砂轮同属这一族但它自己立得住
                || (b instanceof net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
                        && !(b instanceof net.minecraft.world.level.block.GrindstoneBlock));
    }

    /** 层内阶段:清障 0 → 骨架 1 → 贴附 2。 */
    public static int stage(BuildTaskRecord.Target target) {
        if (BuildCellRules.isAirTarget(target)) {
            return 0;
        }
        BlockState state = target.desiredState();
        return state != null && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO) ? 1 : 2;
    }

    /**
     * 这一趟的落位速率(开工时算一次,全程恒定)。
     *
     * <p>目标时长封顶、速率由格数除出来:小工程走下限速率,自然比封顶短;大工程
     * 一开始就更快,总时长收敛到封顶值。不是"越盖越快",也没有到点强制收工。
     */
    public static double paceFor(int cellCount, boolean consumeMaterials) {
        int cells = Math.max(1, cellCount);
        return consumeMaterials
                ? Math.max(SURVIVAL_MIN_RATE, cells / SURVIVAL_TARGET_TICKS)
                : Math.min(FREE_MAX_RATE, cells / FREE_TARGET_TICKS);
    }

    /**
     * 施工预计要多少刻——派发方据此定时限。
     *
     * <p>必须和 {@link #paceFor} 用同一个公式算,不能各拍各的:此前时限按"每格
     * 固定几刻"估,而我把"每秒两格"错记成了"每刻两格",于是最慢档的真实开销
     * (每格十刻)被低估了二十倍,五百格的生存建筑会在盖到一半时被判超时——而
     * 一千四百格以下走的都是这个下限速率,也就是大多数房子。
     */
    public static long estimatedTicks(int cellCount, boolean consumeMaterials) {
        return (long) Math.ceil(Math.max(1, cellCount) / paceFor(cellCount, consumeMaterials));
    }
}
