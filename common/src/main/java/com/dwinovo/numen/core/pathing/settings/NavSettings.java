package com.dwinovo.numen.core.pathing.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 地面寻路全部旋钮的集中定义:成本模型、能力开关、搜索预算、分段与执行参数。
 * 所有字段 public 可变,运行期直接改写即生效;全局单例 {@link #get()}。
 *
 * <p>方块/物品清单字段经由懒加载 getter 暴露(首次访问才触碰注册表),
 * 保证纯逻辑单测在不引导 MC 注册表的情况下也能使用其余数值字段。
 */
public final class NavSettings {

    private static final NavSettings INSTANCE = new NavSettings();

    public static NavSettings get() {
        return INSTANCE;
    }

    private NavSettings() {}

    // ==================== 成本 / 能力开关 ====================

    /** 调试:打开寻路性能探针,{@link com.dwinovo.numen.core.pathing.util.NavProfiler} 按窗口打 [nav-profile] 日志。默认关。 */
    public boolean profile = false;

    /** 允许挖掘方块开路。 */
    public boolean allowBreak = true;
    /** 允许疾跑。 */
    public boolean allowSprint = true;
    /** 允许放置方块搭路。 */
    public boolean allowPlace = true;
    /** 允许动用背包深处(9-35 格)的物品:规划期全背包计入耗材,执行期
     *  自动把耗材/水桶搬进快捷栏。关闭时只认快捷栏与副手。 */
    public boolean allowInventory = true;
    /** 允许把方块放进流体源方块所在格。 */
    public boolean allowPlaceInFluidsSource = true;
    /** 允许把方块放进流动流体所在格。 */
    public boolean allowPlaceInFluidsFlow = true;
    /** 放置一个方块的成本罚金(省方块,不鼓励乱放)。 */
    public double blockPlacementPenalty = 20.0;
    /** 每次挖掘的附加成本(除纯挖掘耗时外的定值)。 */
    public double blockBreakAdditionalPenalty = 2.0;
    /** 每次起跳的附加罚金。 */
    public double jumpPenalty = 2.0;
    /** 水面行走每格附加罚金。 */
    public double walkOnWaterOnePenalty = 3.0;
    /** 允许高空坠落时用水桶接底。 */
    public boolean allowWaterBucketFall = true;
    /** 视水面为可行走地面(默认关,按游泳位语义处理水)。 */
    public boolean assumeWalkOnWater = false;
    /** 视岩浆面为可行走地面(默认关)。 */
    public boolean assumeWalkOnLava = false;
    /** 假定有自动上台阶能力(上一格无需跳跃)。 */
    public boolean assumeStep = false;
    /** 假定行走安全(搭桥时不潜行)。 */
    public boolean assumeSafeWalk = false;
    /** 允许在建筑高度上限起跳。 */
    public boolean allowJumpAtBuildLimit = false;
    /** 允许跑酷跳上高一格的落点。 */
    public boolean allowParkourAscend = true;
    /** 允许对角下降。 */
    public boolean allowDiagonalDescend = false;
    /** 允许对角上升。 */
    public boolean allowDiagonalAscend = false;
    /** 允许原地向下挖。 */
    public boolean allowDownward = true;
    /** 视藤蔓为可攀爬(默认关)。 */
    public boolean allowVines = false;
    /** 允许站上下半台阶。 */
    public boolean allowWalkOnBottomSlab = true;
    /** 允许跑酷跳跃(2-4 格平跳,默认关)。 */
    public boolean allowParkour = false;
    /** 允许跑酷跳跃中途在落点下方放方块(默认关)。 */
    public boolean allowParkourPlace = false;
    /** 破坏成本计入急迫/挖掘疲劳药水效果。 */
    public boolean considerPotionEffects = true;
    /** 液体邻格判定从严:任何相邻液体都禁挖(默认关,只禁源与横流)。 */
    public boolean strictLiquidCheck = false;
    /** 不挖会引发悬空下坠的方块(沙/砾邻格)。 */
    public boolean avoidUpdatingFallingBlocks = true;
    /** 无水情况下可接受的最大坠落高度。 */
    public int maxFallHeightNoWater = 3;
    /** 持水桶时可接受的最大坠落高度。 */
    public int maxFallHeightBucket = 20;
    /** 允许用剑参与挖掘选材。 */
    public boolean useSwordToMine = true;
    /** 保护低耐久工具:耐久低于阈值的工具不参与选材。 */
    public boolean itemSaver = false;
    /** {@link #itemSaver} 的耐久阈值。 */
    public int itemSaverThreshold = 10;
    /** 平速时优先精准采集工具。 */
    public boolean preferSilkTouch = false;
    /** 自动切换最优工具。 */
    public boolean autoTool = true;
    /** 破坏已完成建筑目标的成本乘数。 */
    public double breakCorrectBlockPenaltyMultiplier = 10.0;
    /** 在建筑目标格临时放置错误方块的成本乘数。 */
    public double placeIncorrectBlockPenaltyMultiplier = 2.0;
    /** 建造时是否把任意非空气现有方块视为已可接受。 */
    public boolean buildIgnoreExisting = false;
    /** 建造时是否忽略朝向类方块属性。 */
    public boolean buildIgnoreDirection = false;
    /** 建造时是否把水/液体方块视为已可接受。 */
    public boolean okIfWater = false;
    /** 建造清理时允许从目标上一层附近处理下方方块。 */
    public boolean breakFromAbove = false;
    /** 清理目标可从上方处理时，把上方附近也纳入导航目标。 */
    public boolean goalBreakFromAbove = false;
    /** 分层建造中当前层没有可执行动作时是否跳到下一层。 */
    public boolean skipFailedLayers = false;
    /** 建造任务一次全量重算最多保留的未完成目标数。 */
    public int incorrectSize = 100;
    /** 建造任务每 tick 在玩家附近重新检查的半径。 */
    public int builderTickScanRadius = 5;
    /** 是否只保留离玩家较近的未完成目标窗口。 */
    public boolean distanceTrim = true;
    /** 受保护方块的挖掘速度乘数(0.1 即成本 ×10)。 */
    public double avoidBreakingMultiplier = 0.1;
    /** 启用生物/刷怪笼规避(默认关,关闭时 Favoring 不叠规避球)。 */
    public boolean avoidance = false;
    /** 刷怪笼规避系数(>1 规避,<1 主动靠近)。 */
    public double mobSpawnerAvoidanceCoefficient = 2.0;
    /** 刷怪笼规避半径(格)。 */
    public int mobSpawnerAvoidanceRadius = 16;
    /** 生物规避系数(>1 规避,<1 主动靠近)。 */
    public double mobAvoidanceCoefficient = 1.5;
    /** 生物规避半径(格)。 */
    public int mobAvoidanceRadius = 8;

    // ==================== 搜索 ====================

    /** 启发式权重:按全程可疾跑的乐观单格成本估计。 */
    public double costHeuristic = 3.563;
    /** 一次搜索允许触碰未加载 chunk 边界的次数上限。 */
    public int pathingMaxChunkBorderFetch = 50;
    /** 重算时旧路径上动作成本的折扣系数(抑制路径抖动)。 */
    public double backtrackCostFavoringCoefficient = 0.5;
    /** 松弛与 bestSoFar 更新要求最小改进量 0.01(防浮点噪声反复传播)。 */
    public boolean minimumImprovementRepropagation = true;
    /** 节点表初始容量。 */
    public int pathingMapDefaultSize = 1024;
    /** 节点表负载因子。 */
    public float pathingMapLoadFactor = 0.75f;
    /** 目标所在 chunk 未加载时把目标退化为 XZ 目标。 */
    public boolean simplifyUnloadedYCoord = true;
    /** 首段搜索:找到可用部分路径后的预算(毫秒)。 */
    public long primaryTimeoutMS = 500;
    /** 首段搜索:毫无可用结果时烧满的预算(毫秒)。 */
    public long failureTimeoutMS = 2000;
    /** 接续段搜索的 primary 预算(毫秒)。 */
    public long planAheadPrimaryTimeoutMS = 4000;
    /** 接续段搜索的 failure 预算(毫秒)。 */
    public long planAheadFailureTimeoutMS = 5000;
    /**
     * 单次 A* 展开节点数的【失控安全网】,不是常规限制。正常搜索由时间预算({@link #primaryTimeoutMS}
     * 等)约束:一次搜索探到时间预算耗尽为止,CPU 由有界线程池 + 低优先级线程兜住。
     * 此值设得足够高,以致 5 秒时间预算总是先触发(挖掘寻路约 5 万节点/秒,5 秒也才 ~25 万),
     * 因此它在正常游戏里【永不触发】,只用于兜底一个理论上"时间检查都没能停下"的病态死循环。
     *
     * <p>历史教训:曾经设成 5 万当"节点上限"来压挖钻石卡顿,结果挖矿的大复合目标(几十个矿一起搜)
     * 启发指引弱、要探超过 5 万节点才找到路 → 被砍断误判无路 → 拉黑近处矿、舍近求远。CPU 的事改由
     * 线程池/优先级解决,这里退回纯安全网。命中(极罕见)时返回当前最优半程,与超时同收尾。
     */
    public int maxNodesPerSearch = 2_000_000;

    // ==================== 路径 / 分段 ====================

    /** 在已加载 chunk 边界截断路径(默认关)。 */
    public boolean cutoffAtLoadBoundary = false;
    /** 部分路径截尾系数(段尾质量差,截掉 10%)。 */
    public double pathCutoffFactor = 0.9;
    /** 路径长度达到该值才做截尾。 */
    public int pathCutoffMinimumLength = 30;
    /** 剩余成本低于该 tick 数时开始预计算下一段。 */
    public int planningTickLookahead = 150;
    /** 把下一段拼接进当前路径。 */
    public boolean splicePath = true;
    /** 已执行路径历史长度上限。 */
    public int maxPathHistoryLength = 300;
    /** 历史超限时裁掉的开头步数。 */
    public int pathHistoryCutoffAmount = 50;
    /** 目标失效时取消当前段。 */
    public boolean cancelOnGoalInvalidation = true;

    // ==================== 执行 ====================

    /** 未加载 chunk 里估出的 movement,加载后涨价超过该值即中止。 */
    public double maxCostIncrease = 10.0;
    /** 进入新 movement 时向前复核成本的步数。 */
    public int costVerificationLookahead = 5;
    /** 单个 movement 超出估价该 tick 数即判卡死。 */
    public int movementTimeoutTicks = 100;
    /** 允许 traverse→ascend 连跳疾跑。 */
    public boolean sprintAscends = true;
    /** traverse 冲过头 1-2 格也算成功(不减速回身)。 */
    public boolean overshootTraverse = true;
    /** 允许 descend 疾跑冲进对角步。 */
    public boolean allowOvershootDiagonalDescend = true;
    /** 允许水中疾跑。 */
    public boolean sprintInWater = true;
    /** 挖掘准备期同时向目标走近。 */
    public boolean walkWhileBreaking = true;
    /** 头顶有下坠方块实体时暂停挖掘等待落定。 */
    public boolean pauseMiningForFallingBlocks = true;
    /** 方块交互距离。 */
    public double blockReachDistance = 4.5;
    /** 连续挖掘的破块间隔(tick)。 */
    public int blockBreakSpeed = 6;
    /** 连续右键的间隔(tick)。 */
    public int rightClickSpeed = 4;

    // ==================== 方块 / 物品清单(懒加载,首次访问才触碰注册表) ====================

    private List<Item> acceptableThrowawayItems;
    private List<Block> blocksToAvoid;
    private List<Block> blocksToDisallowBreaking;
    private List<Block> blocksToAvoidBreaking;
    private List<Block> allowBreakAnyway;
    private List<Block> buildIgnoreBlocks;
    private List<Block> okIfAir;
    private List<String> buildIgnoreProperties;
    private Map<Block, List<Block>> buildValidSubstitutes;

    /** 可用作垫路耗材的物品。 */
    public List<Item> acceptableThrowawayItems() {
        if (acceptableThrowawayItems == null) {
            acceptableThrowawayItems = new ArrayList<>(List.of(
                    Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.STONE));
        }
        return acceptableThrowawayItems;
    }

    /** 永不穿行的方块(额外拉黑清单)。 */
    public List<Block> blocksToAvoid() {
        if (blocksToAvoid == null) {
            blocksToAvoid = new ArrayList<>();
        }
        return blocksToAvoid;
    }

    /** 永不挖掘的方块(硬禁令)。 */
    public List<Block> blocksToDisallowBreaking() {
        if (blocksToDisallowBreaking == null) {
            blocksToDisallowBreaking = new ArrayList<>();
        }
        return blocksToDisallowBreaking;
    }

    /** 尽量不挖的功能方块:挖掘成本乘 1/{@link #avoidBreakingMultiplier}。 */
    public List<Block> blocksToAvoidBreaking() {
        if (blocksToAvoidBreaking == null) {
            blocksToAvoidBreaking = new ArrayList<>(List.of(
                    Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.CHEST, Blocks.TRAPPED_CHEST));
        }
        return blocksToAvoidBreaking;
    }

    /** {@link #allowBreak} 关闭时仍允许挖掘的例外方块。 */
    public List<Block> allowBreakAnyway() {
        if (allowBreakAnyway == null) {
            allowBreakAnyway = new ArrayList<>();
        }
        return allowBreakAnyway;
    }

    /** 目标为空气时仍视为可接受的现有方块。 */
    public List<Block> buildIgnoreBlocks() {
        if (buildIgnoreBlocks == null) {
            buildIgnoreBlocks = new ArrayList<>();
        }
        return buildIgnoreBlocks;
    }

    /** 现有空气可接受的目标方块类型。 */
    public List<Block> okIfAir() {
        if (okIfAir == null) {
            okIfAir = new ArrayList<>();
        }
        return okIfAir;
    }

    /** 建造有效性判断中忽略的方块状态属性名。 */
    public List<String> buildIgnoreProperties() {
        if (buildIgnoreProperties == null) {
            buildIgnoreProperties = new ArrayList<>();
        }
        return buildIgnoreProperties;
    }

    /** 目标方块到可接受替代方块的映射。 */
    public Map<Block, List<Block>> buildValidSubstitutes() {
        if (buildValidSubstitutes == null) {
            buildValidSubstitutes = new HashMap<>();
        }
        return buildValidSubstitutes;
    }
}
