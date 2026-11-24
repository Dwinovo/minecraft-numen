package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.permission.Gate;
import com.dwinovo.numen.api.permission.Permission;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一具服务端假玩家直接往一格里写一个方块状态的手,和 {@link Mouse} 并列(同伴的是 {@link NumenPlayer#writer})。鼠标要够得着、按一下
 * 世界回一句话;这只手不看她站在哪儿、离这一格多远、中间隔着什么,把状态原样写进去,世界怎么反应是世界的事。建造就是靠它放方块。
 *
 * <p>{@link #write} 把状态写进这一格({@code setBlock} 带 {@code UPDATE_ALL},邻居由此收到更新),把那一叠物品的数据灌进方块实体
 * (潜影盒里的东西、旗帜的花纹、头颅的主人、蜂箱里的蜂都跟着方块走),再给方块一次"我被放下了"({@code setPlacedBy}:门与床写另一半、
 * 命名牌与告示牌初始化)。然后这一格自己也收一次更新,两种都收:照着周围重算形状(另一半已经立起来,所以门不会被算没),再收一次
 * "邻居变了"的通知(红石灯靠它点亮)。{@code setBlock} 返回假(世界没变,如超出建造高度)就是没写下({@link Write.Unchanged})。
 * {@link #clear} 把这一格写成空气,邻居照原版反应(贴着它的火把掉下来)。
 *
 * <h2>权限</h2>
 * 身体是同伴({@link NumenPlayer})时,每一下写之前先过权限层,和鼠标一样关卡长在身体上、谁驱动她都绕不开:这一格立着要被顶掉的东西先问
 * "能不能挖"({@link com.dwinovo.numen.permission.Verb#BREAK}),再问"能不能放"({@link com.dwinovo.numen.permission.Verb#PLACE},
 * 放的是手里这一叠的物品)。不许就不写,交回裁决;要问主人就不写,交回要问的那一条({@link Write.Refused},和 {@link Mouse.Refusal}
 * 同一种),由驱动者决定怎么问。不是同伴的普通假玩家不过权限层。
 *
 * <p>只在世界所在的线程上调用。
 */
public final class BlockWriter {

    /** 写这一下的结果。 */
    public sealed interface Write {

        /** 写下去了。 */
        record Written() implements Write {}

        /** 世界一格没变:{@code setBlock} 没让写(超出建造高度)。 */
        record Unchanged() implements Write {}

        /** 动手之前权限层说不许,或要问主人:{@link Mouse.Refusal.Denied}、{@link Mouse.Refusal.Asks}。 */
        record Refused(Mouse.Refusal reason) implements Write {}

        Write WRITTEN = new Written();
        Write UNCHANGED = new Unchanged();
    }

    private final ServerPlayer body;

    public BlockWriter(ServerPlayer body) {
        this.body = body;
    }

    /**
     * 把 {@code desired} 写进 {@code pos} 这一格。
     *
     * @param placing 写下去的是这一叠物品放出来的:权限层问的是它的物品,方块实体读它的数据
     */
    public Write write(BlockPos pos, BlockState desired, ItemStack placing) {
        ServerLevel level = body.serverLevel();
        BlockState standing = level.getBlockState(pos);
        if (body instanceof NumenPlayer companion) {
            Gate gate = Permission.gateFor(companion);
            if (!standing.isAir()) {
                Mouse.Refusal asked = Mouse.Refusal.of(gate, level, Action.breakBlock(pos, standing));
                if (asked != null) {
                    return new Write.Refused(asked);
                }
            }
            Mouse.Refusal asked = Mouse.Refusal.of(gate, level, Action.place(pos, standing, placing.getItem()));
            if (asked != null) {
                return new Write.Refused(asked);
            }
        }
        if (!level.setBlock(pos, desired, Block.UPDATE_ALL)) {
            return Write.UNCHANGED;
        }
        BlockItem.updateCustomBlockEntityTag(level, body, pos, placing);
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            entity.applyComponentsFromItemStack(placing);
            entity.setChanged();
        }
        desired.getBlock().setPlacedBy(level, pos, desired, body, placing);
        hearFromNeighbours(level, pos);
        return Write.WRITTEN;
    }

    /** 把这一格写成空气:立着的东西先问"能不能挖"。这一格本来就是空气,什么也不做、算写了。 */
    public Write clear(BlockPos pos) {
        ServerLevel level = body.serverLevel();
        BlockState standing = level.getBlockState(pos);
        if (standing.isAir()) {
            return Write.WRITTEN;
        }
        if (body instanceof NumenPlayer companion) {
            Mouse.Refusal asked = Mouse.Refusal.of(Permission.gateFor(companion), level, Action.breakBlock(pos, standing));
            if (asked != null) {
                return new Write.Refused(asked);
            }
        }
        return level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL) ? Write.WRITTEN : Write.UNCHANGED;
    }

    /** 这一格自己也收一次更新:照着周围重算形状(算成空气就是没留住),还在的话再收一次"邻居变了"。 */
    private static void hearFromNeighbours(ServerLevel level, BlockPos pos) {
        BlockState written = level.getBlockState(pos);
        Block.updateOrDestroy(written, Block.updateFromNeighbourShapes(written, level, pos), level, pos, Block.UPDATE_ALL);
        BlockState now = level.getBlockState(pos);
        if (now.is(written.getBlock())) {
            level.neighborChanged(pos, now.getBlock(), pos);
        }
    }
}
