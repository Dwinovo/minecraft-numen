package com.dwinovo.numen;

import com.dwinovo.numen.scan.BlockSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块变了({@link BlockSearch#onBlockChange})在模组上挂在 {@code ServerLevel.onBlockStateChange},任何原因的变化都经过;
 * Bukkit 没有这样的事件,这里用能拿到的那组(破坏、放置、爆炸、流体、活塞、生长与消退、实体改方块、水桶……)。
 * 入口只负责让热索引更热,对错由取用时现读现核管着,所以漏报不要紧,但不能错报方块。
 *
 * <p>这些事件大多在变化之前触发(拿不到变化之后的状态),所以事件里只记"哪一格、变之前是什么",等这一刻的末尾({@link #flush},
 * 在刻末事件里调)再读这一格此刻真正是什么,变了才报。这样报上去的前后状态是真的,不是推测的。
 */
final class PaperBlockChanges implements Listener {

    /** 这一刻里动过的格:哪个世界、哪一格、第一次动之前是什么状态。 */
    private final Map<Cell, BlockState> touched = new LinkedHashMap<>();

    private record Cell(ServerLevel level, BlockPos pos) {}

    /** 刻末调:把这一刻动过的格读成变化之后的样子,变了的报给索引。 */
    void flush() {
        if (touched.isEmpty()) {
            return;
        }
        Map<Cell, BlockState> done = new LinkedHashMap<>(touched);
        touched.clear();
        done.forEach((cell, before) -> {
            BlockState after = cell.level().getBlockState(cell.pos());
            if (after != before) {
                BlockSearch.onBlockChange(cell.level(), cell.pos(), before, after);
            }
        });
    }

    private void mark(Block block) {
        ServerLevel level = ((CraftWorld) block.getWorld()).getHandle();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        touched.putIfAbsent(new Cell(level, pos), level.getBlockState(pos));
    }

    private void mark(Block block, BlockState before) {
        ServerLevel level = ((CraftWorld) block.getWorld()).getHandle();
        touched.putIfAbsent(new Cell(level, new BlockPos(block.getX(), block.getY(), block.getZ())), before);
    }

    private void markAll(List<Block> blocks) {
        blocks.forEach(this::mark);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBreak(BlockBreakEvent event) {
        mark(event.getBlock());
    }

    /** 放置时方块已经在那一格了,变之前的状态是被它替掉的那个。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlace(BlockPlaceEvent event) {
        mark(event.getBlockPlaced(), ((CraftBlockState) event.getBlockReplacedState()).getHandle());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockExplode(BlockExplodeEvent event) {
        markAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEntityExplode(EntityExplodeEvent event) {
        markAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onFluid(BlockFromToEvent event) {
        mark(event.getToBlock());
    }

    /** 活塞推动的每一块,连同它们被推到的那一格和活塞臂。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPush(BlockPistonExtendEvent event) {
        for (Block moved : event.getBlocks()) {
            mark(moved);
            mark(moved.getRelative(event.getDirection()));
        }
        mark(event.getBlock().getRelative(event.getDirection()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPull(BlockPistonRetractEvent event) {
        BlockFace direction = event.getDirection();
        for (Block moved : event.getBlocks()) {
            mark(moved);
            mark(moved.getRelative(direction));
        }
        mark(event.getBlock().getRelative(event.getDirection().getOppositeFace()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onGrow(BlockGrowEvent event) {
        mark(event.getBlock());
    }

    /** 成形与蔓延(雪、冰、混凝土粉、草的蔓延、火的蔓延)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onForm(BlockFormEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onFade(BlockFadeEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBurn(BlockBurnEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDecay(LeavesDecayEvent event) {
        mark(event.getBlock());
    }

    /** 实体改方块(掉落的沙子落地、末影人搬走、绵羊吃草……)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onEntityChange(EntityChangeBlockEvent event) {
        mark(event.getBlock());
    }

    /** 树木与蘑菇长成、骨粉催熟:动了一批格。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onStructureGrow(StructureGrowEvent event) {
        List<Block> blocks = new ArrayList<>();
        event.getBlocks().forEach(state -> blocks.add(state.getBlock()));
        markAll(blocks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onFertilize(BlockFertilizeEvent event) {
        event.getBlocks().forEach(state -> mark(state.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBucketEmpty(PlayerBucketEmptyEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBucketFill(PlayerBucketFillEvent event) {
        mark(event.getBlock());
    }
}
