package com.dwinovo.numen.core.pathing.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * 地面寻路的引擎与服主参数:总开关(天花板)、搜索预算、分段与执行参数、
 * 建造校验参数。所有字段 public 可变,运行期直接改写即生效;全局单例 {@link #get()}。
 *
 * <p>模型该碰的那部分——这一次导航能不能改地形、能不能跑酷、各项罚金、
 * 每类格子的代价——不在这里,在按次传值的
 * {@link com.dwinovo.numen.core.pathing.spec.RouteSpec};规格只能在这里的总开关
 * 之下收紧。判据:模型会想改的是规格,服主定的上限与引擎自身的参数是设置。
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

    // ==================== 总开关 / 引擎假设 ====================

    /** 调试:打开寻路性能探针,{@link com.dwinovo.numen.core.pathing.util.NavProfiler} 按窗口打 [nav-profile] 日志。默认关。 */
    public boolean profile = false;

    /** 允许挖掘方块开路(总开关;规格的 alter 在其下生效)。 */
    public boolean allowBreak = true;
    /** 允许疾跑(总开关;规格的 sprint 在其下生效)。 */
    public boolean allowSprint = true;
    /** 允许放置方块搭路(总开关)。 */
    public boolean allowPlace = true;
    /** 允许动用背包深处(9-35 格)的物品:规划期全背包计入耗材,执行期
     *  自动把耗材/水桶搬进快捷栏。关闭时只认快捷栏与副手。 */
    public boolean allowInventory = true;
    /** 允许把方块放进流体源方块所在格。 */
    public boolean allowPlaceInFluidsSource = true;
    /** 允许把方块放进流动流体所在格。 */
    public boolean allowPlaceInFluidsFlow = true;
    /** 允许高空坠落时用水桶接底。 */
    public boolean allowWaterBucketFall = true;
    /** 假定有自动上台阶能力(上一格无需跳跃)。 */
    public boolean assumeStep = false;
    /** 假定行走安全(搭桥时不潜行)。 */
    public boolean assumeSafeWalk = false;
    /** 允许在建筑高度上限起跳。 */
    public boolean allowJumpAtBuildLimit = false;
    /** 破坏成本计入急迫/挖掘疲劳药水效果。 */
    public boolean considerPotionEffects = true;
    /** 不挖会引发悬空下坠的方块(沙/砾邻格)。 */
    public boolean avoidUpdatingFallingBlocks = true;
    /** 每点摔落伤害折算的代价(tick 当量)。走一格约 4.6,默认 20 即"每掉半颗心
     *  宁可多绕四格多路"——疼不再免费,但摔不死的高度依然是路。 */
    public double fallDamageCostPerPoint = 20.0;
    /** 持水桶时可接受的最大坠落高度。 */
    public int maxFallHeightBucket = 20;
    /** 允许用剑参与挖掘选材。 */
    public boolean useSwordToMine = true;
    /** 保护低耐久工具:耐久低于阈值的工具不参与选材。 */
    public boolean itemSaver = false;
    /** {@link #itemSaver} 的耐久阈值。 */
    public int itemSaverThreshold = 10;
    /** 自动切换最优工具。 */
    public boolean autoTool = true;
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
    /*
     * 搜索预算按展开的节点数计,不按时间:同一个起点、目标与地形,在任何机器、任何负载下搜到的都是
     * 同一个结论。按时间计的话,搜索线程(最低优先级)在忙机器上被晾着、JIT 还没热、正赶上 GC,
     * 同样的时间里展开的节点就少,闲时二十毫秒找到的路,忙时交出半截路或"无路"。
     *
     * 取值:闲时整套 GameTest 实测每 CPU 毫秒展开约 50 个节点(p10–p90 为 43–59),与挖掘寻路约 5 万节点/秒
     * 的经验一致;下面四个数在这样的机器上约合半秒、两秒、四秒、五秒的搜索。
     * 整套 GameTest 里走到目标的搜索最多展开约 3700 个节点,远在首段预算之内;挖矿的大复合目标启发
     * 指引弱,探几万个节点才找到路是常事,预算不能再往下压,否则就是"误判无路 → 舍近求远"。
     *
     * 搜索都在后台线程池上跑(冻结快照),节点预算也就是一次搜索能花的算力上限,主线程不受它影响;
     * 慢机器上同样的节点数要多等一会儿,等待期间任务期限按规划在飞冻结。
     */
    /** 首段搜索:已有可用部分路径(离起点 5 格以上)之后的预算(节点数)。 */
    public int primaryNodes = 25_000;
    /** 首段搜索:毫无可用结果时烧满的预算(节点数),也是首段搜索展开节点数的上限。 */
    public int failureNodes = 100_000;
    /** 接续段搜索的 primary 预算(节点数)。 */
    public int planAheadPrimaryNodes = 200_000;
    /** 接续段搜索的 failure 预算(节点数),也是接续段搜索展开节点数的上限。 */
    public int planAheadFailureNodes = 250_000;

    // ==================== 规划查询 ====================

    /**
     * 出备选路线的惩罚倍率:上一条路踩过的每一格,踩价按它计(附加 (倍率-1)×单格步行成本,
     * 见 {@code RoutePlanner})。惩罚法而不是随机扰动——路线多样性的研究结论是惩罚法得到的
     * 备选重叠少得多,且可复现。
     */
    public double routeAlternativePenaltyFactor = 3.0;
    /** 备选与已有候选的格位重叠率高于此值即丢弃(0-1)。 */
    public double routeAlternativeMaxOverlap = 0.7;
    /** 每个同伴的路线簿最多存几条候选,超出淘汰最早的。 */
    public int routeBookCapacity = 6;

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
    /** 连续挖掘的破块间隔(tick)。 */
    public int blockBreakSpeed = 6;
    /** 连续右键的间隔(tick)。 */
    public int rightClickSpeed = 4;

    // ==================== 方块 / 物品清单(懒加载,首次访问才触碰注册表) ====================

    private List<Block> allowBreakAnyway;
    private List<Block> buildIgnoreBlocks;
    private List<Block> okIfAir;
    private List<String> buildIgnoreProperties;
    private Map<Block, List<Block>> buildValidSubstitutes;

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
