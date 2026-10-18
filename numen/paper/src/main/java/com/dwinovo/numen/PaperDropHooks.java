package com.dwinovo.numen;

import com.dwinovo.numen.act.Drops;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemMergeEvent;

import net.minecraft.world.damagesource.DamageSource;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 她这一挖的掉落物去了哪({@link Drops})在模组上靠 Mixin 挂在原版上,Paper 上由事件给出;每个入口的 javadoc 写着调用方要保证什么,
 * 这里逐条对着做。都在 MONITOR 优先级、跳过已取消的。账在她自己的玩家刻里就可能结算,所以事件里当场交,不往后拖。
 * <ul>
 *   <li><b>掉落物</b>:{@code BlockDropItemEvent} 在 {@code destroyBlock} 里触发,带着这一挖生成的全部掉落物(连带碎掉的火把、
 *       箱子里的东西——Paper 把这一挖期间进世界的物品实体都截下来交给它),所以这一段起止就包住事件里这些实体;事件之后它们才真进世界;</li>
 *   <li><b>被捡</b>:{@code EntityPickupItemEvent} 在件数被减掉之前触发,是真正收下它的实体;</li>
 *   <li><b>并堆</b>:{@code ItemMergeEvent} 在并之前触发,按原版的并法({@link ItemEntity#merge(ItemStack, ItemStack, int)})
 *       把并完以后两堆各剩几件算好交来;</li>
 *   <li><b>被毁</b>:掉落物挨打的 {@code EntityDamageEvent} 带着伤害来源,打没了触发 {@code EntityRemoveEvent}(原因 DEATH),
 *       这就是"被这一下打没了",交给 {@link Drops#destroyed}。</li>
 * </ul>
 */
final class PaperDropHooks implements Listener {

    /** 刚挨了打的掉落物和打它的东西,打没了的那一下用。弱引用:没打没的随实体一起放掉。 */
    private final Map<ItemEntity, DamageSource> hitBy = new WeakHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onDropped(BlockDropItemEvent event) {
        Drops.breakStarts(((CraftPlayer) event.getPlayer()).getHandle());
        try {
            for (Item item : event.getItems()) {
                Drops.added(handle(item));
            }
        } finally {
            Drops.breakEnds();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onPickedUp(EntityPickupItemEvent event) {
        Drops.taken(handle(event.getItem()), ((CraftLivingEntity) event.getEntity()).getHandle());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onMerging(ItemMergeEvent event) {
        ItemEntity from = handle(event.getEntity());
        ItemEntity into = handle(event.getTarget());
        int intoAfter = ItemEntity.merge(into.getItem().copy(), from.getItem().copy(), 64).getCount();
        int moved = intoAfter - into.getItem().getCount();
        Drops.merged(into, from, moved, intoAfter, from.getItem().getCount() - moved);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item) {
            hitBy.put(handle(item), ((CraftDamageSource) event.getDamageSource()).getHandle());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onRemoved(EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.DEATH && event.getEntity() instanceof Item item) {
            DamageSource source = hitBy.remove(handle(item));
            if (source != null) {
                Drops.destroyed(handle(item), source);
            }
        }
    }

    private static ItemEntity handle(Item item) {
        return (ItemEntity) ((CraftItem) item).getHandle();
    }
}
