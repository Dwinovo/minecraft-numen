package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.cache.LoadedOnlyView;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 单格判据的唯一出处:这一格能不能动、该不该动、是不是注定动不了。
 * 施工循环、轮扫对账与材料报价三处共用——判据分叉的直接后果就是
 * "报价索要永远不会被放置的格子的材料"那类账目谎言,只此一份。
 */
final class BuildCellRules {

    private final NumenPlayer player;
    private final BuildTaskRecord r;

    BuildCellRules(NumenPlayer player, BuildTaskRecord r) {
        this.player = player;
        this.r = r;
    }

    /**
     * 只读已加载区块的取态——未加载处当空气。
     *
     * <p>{@code level.getBlockState} 在服务端会<b>同步生成区块</b>(它内部要的是 FULL
     * 状态,拿不到就现场生成)。判定这一族的读点要么在开工前置上(盘料得扫全图纸),
     * 要么在每刻的热路径上——一个远处锚点就能让她把图纸覆盖的所有区块现场生成一遍,
     * 玩家看到的是一次可见卡顿。轮扫那处本来就用的是钳制视图,这几处得跟上同一条纪律,
     * 否则六个读点里只挡住了一个。
     */
    BlockState peek(BlockPos pos) {
        return LoadedOnlyView.of(player.level()).getBlockState(pos);
    }

    static boolean isAirTarget(BuildTaskRecord.Target target) {
        return target.block() == net.minecraft.world.level.block.Blocks.AIR;
    }

    /** 计费判据在 {@link BuildTaskRecord.Target#costsMaterial()}——盘点工具与这里共用。 */
    boolean costsMaterial(BuildTaskRecord.Target target) {
        return !isAirTarget(target) && target.costsMaterial();
    }

    /**
     * 这一格本档不让动吗——让路的判定只有这一处。
     *
     * <p>不让动的格子<b>不进待建集、也算作了结</b>。若只是"放的时候跳过",它每一遍
     * 都会重新排进顺序、每一遍都放不下去,整栋楼陪着它重试到超时,而那一格从第一遍
     * 起就已经注定动不了。
     */
    boolean blockedByMode(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState current = peek(pos);
        if (!r.replaceMode.allows(current, target.desiredState())) {
            return true;
        }
        // 玩家的箱子不能被一堵墙盖掉。让路的档位管"石头挡路要不要顶掉",这一条
        // 管"带方块实体的方块要不要动"——少砌一格墙是遗憾,清掉一箱子东西是事故。
        if (r.replaceBlockEntities || current.isAir()) {
            return false;
        }
        if (current.hasBlockEntity() && !target.matches(current)) {
            return true;
        }
        // 双格方块连另一半一起看:任一半压着方块实体就都不动
        BlockPos other = otherHalfOf(pos, target.desiredState());
        return other != null && peek(other).hasBlockEntity();
    }

    /**
     * 这一格<b>注定</b>动不了吗——世界边界之外,或者砸不动的东西挡着。
     *
     * <p>和"这一刻放不下去"要分开:后者(区块没加载、她自己站在那格里、材料没到)
     * 下一遍就可能变,该留在待建集里;前者从第一遍起就不会变,留着只会让整栋楼
     * 每一遍都为它重排一次顺序、重试一次,一直耗到超时。
     */
    boolean hopeless(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        if (!player.level().getWorldBorder().isWithinBounds(pos)) {
            return true;
        }
        // 出了建造高度就是写不进去:setBlock 直接返回假、世界毫无变化。留在待办里的
        // 后果是每遍白扣一件料——那一格永远对不上,而扣料照扣。
        if (player.level().isOutsideBuildHeight(pos)) {
            return true;
        }
        return unbreakableAt(pos, target.desiredState());
    }

    /**
     * 这一格砸不动吗——基岩、末地传送门框架这类 {@code destroySpeed == -1} 的东西。
     *
     * <p>不判的话她会对着基岩一遍遍地清、一遍遍地失败,直到超时。<b>双格方块要连
     * 它的另一半一起查</b>:床的另一半在朝向那一格,门与高草的另一半在正上方——
     * 只查自己那一格,会出现"下半放下去了、上半卡在基岩里"的半截货。
     */
    private boolean unbreakableAt(BlockPos pos, BlockState desired) {
        var level = player.level();
        if (peek(pos).getDestroySpeed(level, pos) == -1) {
            return true;
        }
        BlockPos other = otherHalfOf(pos, desired);
        return other != null && peek(other).getDestroySpeed(level, other) == -1;
    }

    /**
     * 双格方块的另一半在哪:床看朝向那一格,门与高草看正上方。
     *
     * <p>只查自己那一格,会出现"下半放下去了、上半卡在基岩里"或者"下半盖住了玩家
     * 箱子的上半"这类半截货,所以砸不动与箱子保护两处都要连它一起看。
     */
    static BlockPos otherHalfOf(BlockPos pos, BlockState desired) {
        if (desired == null) {
            return null;
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART)
                && desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return pos.relative(desired.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return pos.above();
        }
        return null;
    }

    /**
     * 谁都不豁免——包括同伴自己:身体占着/正落进的格子不可放置。
     *
     * <p>自己的身体用直接几何判交,不走实体分区索引——假人在索引里会漏检
     * (法医快照抓到过"双脚站在目标格里却放行",随后往自己身上落方块把自己
     * 封进树叶)。我在哪儿,不需要问索引。
     */
    boolean blockedByEntity(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(player.level(), pos, CollisionContext.of(player));
        if (shape.isEmpty()) {
            return false;
        }
        VoxelShape placed = shape.move(pos.getX(), pos.getY(), pos.getZ());
        AABB body = player.getBoundingBox();
        for (AABB piece : placed.toAabbs()) {
            if (piece.intersects(body)) {
                return true;
            }
        }
        return !player.level().isUnobstructed(player, placed);
    }
}
