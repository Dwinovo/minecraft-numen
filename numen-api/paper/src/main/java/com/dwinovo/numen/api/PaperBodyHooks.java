package com.dwinovo.numen.api;

import com.dwinovo.numen.api.entity.Belongings;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.permission.PlacedBlocks;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.advancement.CraftAdvancement;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.UUID;

/**
 * 模组上靠 Mixin 挂在原版上的几处,Paper 上由事件给出——每一处对应一个 common 入口,入口的 javadoc 写着调用方要保证什么,
 * 这里逐条对着做:
 * <ul>
 *   <li>碰到放不下的掉落物 → {@link NumenPlayer#touchedItem};</li>
 *   <li>谁放的这一格 → {@link PlacedBlocks#placedBy};</li>
 *   <li>进度奖励 → {@link NumenEvents#advancementReward}。</li>
 * </ul>
 * 都在 MONITOR 优先级、跳过已取消的:别的插件对这件事做完决定以后,才轮到我们记。
 */
final class PaperBodyHooks implements Listener {

    /**
     * 掉落物碰到了她。事件只在拾取冷却已过时才触发,而且在原版的归属检查之前——所以"没指名给别人"这条自己判
     * (原版是 {@code target == null || target 是她})。放不放得下由 {@code touchedItem} 按原版背包的规则判。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTouched(PlayerAttemptPickupItemEvent event) {
        if (handle(event.getPlayer()) instanceof NumenPlayer her) {
            UUID target = event.getItem().getOwner();
            if (target == null || target.equals(her.getUUID())) {
                her.touchedItem((ItemEntity) ((CraftItem) event.getItem()).getHandle());
            }
        }
    }

    /**
     * 经物品落位的放置(含双格方块,另一半由 {@code placedBy} 自己带出来)。事件触发时方块已经在那一格;被取消的不记。
     * 命令、活塞、生长写下的不触发这个事件,本来就不是"玩家放的"。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPlaced(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        ServerLevel level = ((CraftWorld) placed.getWorld()).getHandle();
        PlacedBlocks.placedBy(level, new BlockPos(placed.getX(), placed.getY(), placed.getZ()), handle(event.getPlayer()));
    }

    /**
     * 她达成一个进度。事件在发奖励<b>之前</b>触发,奖励在同一个调用里随后发到她身上:这里拍背包与经验的快照,
     * 等这个调用返回再比,变了就告诉她。没变的不说——配方解锁这类没有物品奖励的进度天天在达成。
     * 排的是 {@code scheduleOnMain}:{@code execute} 在主线程上当场就跑(只有已经在跑某个任务时才排队),那样比的时候奖励还没发。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!(handle(event.getPlayer()) instanceof NumenPlayer her)) {
            return;
        }
        AdvancementHolder holder = ((CraftAdvancement) event.getAdvancement()).getHandle();
        Belongings before = Belongings.of(her);
        her.server.scheduleOnMain(() -> {
            String change = before.changeTo(her);
            if (!change.isEmpty()) {
                String id = holder.id().toString();
                NumenEvents.advancementReward(her, id,
                        holder.value().display().map(d -> d.getTitle().getString()).orElse(id), change);
            }
        });
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }
}
