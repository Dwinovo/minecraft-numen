package com.dwinovo.numen.core.task.base;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "只捡这次战果"的掉落物簿记:先快照现场已有的物品实体(id 与堆叠数),
 * 事后按差集发现新掉落——新 id 是新掉落,旧 id 堆叠数变大(掉落并入了
 * 已有实体)也是。近战的战利品相位与钓鱼的收获相位共用这份发现/追踪
 * 逻辑;两者怎么走过去捡、捡不到算不算失败,是各自的产品语义,留在
 * 任务里。
 */
public final class DropTracker {

    private final Set<Integer> preexisting = new HashSet<>();
    private final Map<Integer, Integer> preexistingCounts = new HashMap<>();
    private final Set<Integer> tracked = new LinkedHashSet<>();

    /** 快照 {@code box} 内现有物品实体——之后的 discover 只认快照外的新面孔。 */
    public void rememberExisting(Level level, AABB box) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            preexisting.add(item.getId());
            preexistingCounts.put(item.getId(), item.getItem().getCount());
        }
    }

    /** 把 {@code box} 内快照之外(或堆叠数长了)的物品实体收入追踪。 */
    public void discover(Level level, AABB box) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            int id = item.getId();
            if (!preexisting.contains(id)
                    || item.getItem().getCount() > preexistingCounts.getOrDefault(id, 0)) {
                tracked.add(id);
            }
        }
    }

    /** 清掉已被拾取/消失的追踪项。 */
    public void prune(ServerLevel level) {
        tracked.removeIf(id -> {
            Entity entity = level.getEntity(id);
            return !(entity instanceof ItemEntity) || entity.isRemoved();
        });
    }

    /** 仍在世且未被放弃({@code skipped})的追踪掉落物。 */
    public List<ItemEntity> live(ServerLevel level, Set<Integer> skipped) {
        List<ItemEntity> out = new ArrayList<>();
        for (int id : tracked) {
            Entity entity = level.getEntity(id);
            if (entity instanceof ItemEntity item && !item.isRemoved() && !skipped.contains(id)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 离身体最近的在世追踪掉落物。 */
    public Optional<ItemEntity> nearest(ServerLevel level, Player player, Set<Integer> skipped) {
        return live(level, skipped).stream().min(Comparator.comparingDouble(player::distanceToSqr));
    }

    /** 是否有任何在世追踪项(含被 skip 的——它们还在地上,只是不去捡)。 */
    public boolean anyTrackedAlive(ServerLevel level) {
        return !live(level, Set.of()).isEmpty();
    }

    /** 开始新一轮追踪(保留快照,清追踪集)。 */
    public void resetTracking() {
        tracked.clear();
    }

    /** 整体清空(快照与追踪集)。 */
    public void clear() {
        preexisting.clear();
        preexistingCounts.clear();
        tracked.clear();
    }
}
