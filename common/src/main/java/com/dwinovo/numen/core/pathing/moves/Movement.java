package com.dwinovo.numen.core.pathing.moves;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 移动原语抽象基类:一条"从 src 到 dest"的最小可执行动作,
 * 自带成本计算(规划期)与逐 tick 状态机(执行期)。
 *
 * <p>执行侧通用框架在 {@link #update()}:先由子类推进状态机,再叠加
 * 水中上浮强跳与卡墙自救,最后把本 tick 的按键表交给执行层钩子。
 * 准备阶段({@link #prepared}):等待落沙实体落定、把仍挡路的
 * toBreak 逐个交给 {@link #beginBreaking} 钩子挖掉。
 */
public abstract class Movement {

    protected final ServerPlayer player;

    protected final BlockPos src;
    protected final BlockPos dest;

    /** 本动作开跑前需要挖穿的格。 */
    protected final BlockPos[] positionsToBreak;

    /** 本动作开跑前需要放上方块的格(无则 null)。 */
    protected final BlockPos positionToPlace;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    /** 缓存成本;override 机制允许外部钉入更严的估价。 */
    private Double cost;

    private Set<BlockPos> validPositionsCached;

    /** 仍需挖穿的格(懒算缓存,{@link #resetBlockCache()} 后按当前世界重算)。 */
    protected List<BlockPos> toBreakCached;
    /** 仍需放上方块的格(懒算缓存)。 */
    protected List<BlockPos> toPlaceCached;
    /** 会用身体挤进去的格(懒算缓存;仅对角移动产出非空)。 */
    protected List<BlockPos> toWalkIntoCached;

    /** 成本是否是在 dest 所在 chunk 已加载时算出的(执行期涨价豁免用)。 */
    private Boolean calculatedWhileLoaded;

    protected Movement(ServerPlayer player, BlockPos src, BlockPos dest,
                       BlockPos[] toBreak, BlockPos toPlace) {
        this.player = player;
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(ServerPlayer player, BlockPos src, BlockPos dest, BlockPos[] toBreak) {
        this(player, src, dest, toBreak, null);
    }

    // ==================== 成本 ====================

    /** 已算出的成本;未算先抛(调用方须先走 getCost(context))。 */
    public double getCost() {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            MutableMoveResult result = new MutableMoveResult();
            cost = calculateCost(context, result);
        }
        return cost;
    }

    /** 重算成本(丢弃缓存与 override)。 */
    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    /** 外部钉入成本(取"算时成本与节点差的较严者"时用)。 */
    public void override(double cost) {
        this.cost = cost;
    }

    /**
     * 计算本动作成本;动态落点的动作把实际落点写进 result。
     * 不可行返回 {@link ActionCosts#COST_INF}。
     */
    public abstract double calculateCost(CalculationContext context, MutableMoveResult result);

    // ==================== 合法过程位 ====================

    /** 执行期允许身体出现的格集合(重定位锚)。 */
    protected abstract Set<BlockPos> calculateValidPositions();

    public Set<BlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    /**
     * 当前身位是否属于本动作的合法过程位集合。脚下不在集合里时,
     * 再按 {@link #pathStart(ServerPlayer)} 算一个假起点(脚下不可站时
     * 取 3×3 邻格/下一格的支撑点)——重算/回退后,整体路径起点不一定
     * 属于本移动自身的 {src,dest} 集合,假起点兜住这种情形。
     */
    /**
     * 脚位约定:实体坐标 y 加 0.1251(灵魂沙/农田顶面矮一截仍归上格),
     * 落在台阶格里再上抬一格。执行器重定位、validPositions 与本类状态
     * 机的到达/失败判定**全部**用这一把尺,避免半砖/灵魂沙顶面错位一格
     * 导致 SUCCESS 推进与回退扫循环。
     */
    public static BlockPos feet(ServerPlayer player) {
        BlockPos f = BlockPos.containing(
                player.position().x, player.position().y + 0.1251, player.position().z);
        BlockState at = player.level().getBlockState(f);
        // 楼梯与半砖同理:踩在矮的那半格上时,实体 y 只比格底高半格,加完偏移
        // 仍落在该格自身里,而寻路模型认定人站在它<b>上面</b>那一格。两把尺不
        // 一致,执行器就会认为"人不在本动作的合法位上",前后重定位都对不上,
        // 动作硬撑到超时——而这栋房子越往高层楼梯越密,正好卡在爬升的关口。
        if (at.getBlock() instanceof SlabBlock || at.getBlock() instanceof StairBlock) {
            return f.above();
        }
        return f;
    }

    protected boolean playerInValidPosition() {
        BlockPos feet = feet(player);
        if (getValidPositions().contains(feet)) {
            return true;
        }
        BlockPos fakeStart = pathStart(player);
        return getValidPositions().contains(fakeStart);
    }

    /**
     * 脚下不可站时的假起点:在地面 → 3×3 邻格按水平距离取最近四个,
     * 第一个下可站、本格与上格可穿的格;空中 → 再下一格可站则用脚下格。
     * 其余情况用脚位。与 PathingCore.pathStart 同一语义,提取为基类静态
     * 助手供 Movement 子类(如 Downward 的 UNREACHABLE 判定)复用。
     */
    public static BlockPos pathStart(ServerPlayer player) {
        BlockPos feet = feet(player);
        var level = player.level();
        if (MovementHelper.canWalkOn(level, feet.below())) {
            return feet;
        }
        if (player.onGround()) {
            double playerX = player.position().x;
            double playerZ = player.position().z;
            java.util.List<BlockPos> closest = new java.util.ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    closest.add(new BlockPos(feet.getX() + dx, feet.getY(), feet.getZ() + dz));
                }
            }
            closest.sort(java.util.Comparator.comparingDouble(pos ->
                    ((pos.getX() + 0.5) - playerX) * ((pos.getX() + 0.5) - playerX)
                    + ((pos.getZ() + 0.5) - playerZ) * ((pos.getZ() + 0.5) - playerZ)));
            for (int i = 0; i < 4; i++) {
                BlockPos possibleSupport = closest.get(i);
                double xDist = Math.abs((possibleSupport.getX() + 0.5) - playerX);
                double zDist = Math.abs((possibleSupport.getZ() + 0.5) - playerZ);
                if (xDist > 0.8 && zDist > 0.8) {
                    continue;
                }
                if (MovementHelper.canWalkOn(level, possibleSupport.below())
                        && MovementHelper.canWalkThrough(level, possibleSupport)
                        && MovementHelper.canWalkThrough(level, possibleSupport.above())) {
                    return possibleSupport;
                }
            }
        } else {
            if (MovementHelper.canWalkOn(level, feet.below().below())) {
                return feet.below();
            }
        }
        return feet;
    }

    // ==================== 执行状态机 ====================

    /**
     * 每 tick 推进一次。通用框架:强制关闭飞行能力(走地面物理)→
     * 子类状态机 → 水中且低于目标高度时强按跳(上浮)→ 卡墙时先换上
     * 对该方块最优工具再按左键 → 视角与按键交执行层钩子,按键先清
     * 后设、终态清空。
     */
    public MovementStatus update() {
        // 强制关闭飞行能力:寻路执行期走地面物理(跳跃/下落),不被外部
        // 置真的飞行状态干扰(对应 Baritone Movement.java:124)
        player.getAbilities().flying = false;
        currentState = updateState(currentState);
        BlockPos feet = feet(player);
        if (MovementHelper.isLiquid(player.level().getBlockState(feet))
                && player.getY() < dest.getY() + 0.6) {
            currentState.setInput(Input.JUMP, true);
        }
        if (player.isInWall()) {
            // 卡墙自救:先换上对当前准星命中方块最优的工具再按左键,
            // 破墙速度不拖(对应 Baritone Movement.java:129-132)
            BlockState hitState = crosshairBlockState();
            if (hitState != null && player instanceof com.dwinovo.numen.entity.NumenPlayer np) {
                com.dwinovo.numen.core.task.base.ToolSelect.holdBestTool(np, hitState);
            }
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        if (currentState.getTarget().hasRotation()) {
            applyRotation(currentState.getTarget());
        }
        clearInputs();
        currentState.getInputStates().forEach(this::applyInput);
        currentState.getInputStates().clear();

        if (currentState.getStatus().isComplete()) {
            clearInputs();
        }
        return currentState.getStatus();
    }

    /** 玩家准星当前命中的方块状态;未命中返回 null。 */
    private BlockState crosshairBlockState() {
        double reach = NavSettings.get().blockReachDistance;
        HitResult hit = player.pick(reach, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return player.level().getBlockState(((BlockHitResult) hit).getBlockPos());
        }
        return null;
    }

    /**
     * 准备阶段:toBreak 里任一格上有下坠方块实体(且开了等待开关)
     * → 等待,不挖不动;任一格仍不可穿行 → 交给 {@link #beginBreaking}
     * 挖,返回未就绪。全部通透即就绪(WAITING 后不再重查)。
     */
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        for (BlockPos pos : positionsToBreak) {
            if (NavSettings.get().pauseMiningForFallingBlocks
                    && !player.level().getEntitiesOfClass(FallingBlockEntity.class,
                            new AABB(0, 0, 0, 1, 1.1, 1).move(pos)).isEmpty()) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(player.level(), pos)) {
                beginBreaking(state, pos);
                return false;
            }
        }
        return true;
    }

    /**
     * 状态机推进,子类覆写并先走本实现:未就绪 → PREPPING;
     * 就绪后 PREPPING → WAITING → RUNNING。
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }
        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }
        return state;
    }

    /** 当前是否可被安全中断(默认恒可;悬空放置中的子类覆写)。 */
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState state) {
        return true;
    }

    /** 重置状态机(路径回退重执行时用)。 */
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    // ==================== 执行层钩子(经注入代理落地) ====================

    /**
     * 执行代理:四个钩子的真实落点。移动原语只描述"要看哪、按什么键、
     * 挖哪格",代理负责把这些落到实体上(视角步进、输入字段、渐进挖掘)。
     */
    public interface ExecutionDelegate {

        /** 开挖一格:选可视面、转头,视线就位后按左键。 */
        void beginBreaking(MovementState state, BlockPos pos);

        /** 应用期望视角(按鼠标步进量化逼近,不瞬间对准)。 */
        void applyRotation(MovementState.MovementTarget target);

        /** 清空全部按键。 */
        void clearInputs();

        /** 应用单个按键。 */
        void applyInput(Input input, boolean held);
    }

    private ExecutionDelegate executionDelegate;

    /** 注入执行代理;未注入时四个钩子为空操作(纯规划用途)。 */
    public void setExecutionDelegate(ExecutionDelegate delegate) {
        this.executionDelegate = delegate;
    }

    /** 开挖一格,转发执行代理。 */
    protected void beginBreaking(MovementState state, BlockPos pos) {
        if (executionDelegate != null) {
            executionDelegate.beginBreaking(state, pos);
        }
    }

    /** 应用期望视角,转发执行代理。 */
    protected void applyRotation(MovementState.MovementTarget target) {
        if (executionDelegate != null) {
            executionDelegate.applyRotation(target);
        }
    }

    /** 清空全部按键,转发执行代理。 */
    protected void clearInputs() {
        if (executionDelegate != null) {
            executionDelegate.clearInputs();
        }
    }

    /** 应用单个按键,转发执行代理。 */
    protected void applyInput(Input input, boolean held) {
        if (executionDelegate != null) {
            executionDelegate.applyInput(input, held);
        }
    }

    // ==================== 元数据 ====================

    public BlockPos getSrc() {
        return src;
    }

    public BlockPos getDest() {
        return dest;
    }

    public BlockPos getDirection() {
        return dest.subtract(src);
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }

    /** 丢弃三类格集缓存,下次查询按当前世界重算。 */
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    /** 此刻仍不可穿行、需要挖掉的格(缓存到 {@link #resetBlockCache()})。 */
    public List<BlockPos> toBreak(net.minecraft.world.level.BlockGetter level) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(level, pos)) {
                result.add(pos);
            }
        }
        toBreakCached = result;
        return result;
    }

    /** 此刻仍不可站立、需要放上方块的格(缓存到 {@link #resetBlockCache()})。 */
    public List<BlockPos> toPlace(net.minecraft.world.level.BlockGetter level) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(level, positionToPlace)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    /** 会用身体挤进去的格(基类恒空;对角移动覆写产出切角柱)。 */
    public List<BlockPos> toWalkInto(net.minecraft.world.level.BlockGetter level) {
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos getToPlace() {
        return positionToPlace;
    }

    /** 记录成本计算时 dest 所在 chunk 是否已加载。 */
    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.isLoaded(dest.getX(), dest.getZ());
    }

    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }
}
