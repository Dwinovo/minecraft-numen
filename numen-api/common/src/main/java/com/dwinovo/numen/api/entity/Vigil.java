package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.sdk.NearbyEntities;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 她四周两件值得说的事的看护,每服务端 tick 问一次({@code CompanionTickDispatcher}):
 * <ul>
 *   <li><b>被锁定</b>:半径({@link NearbyEntities#RADIUS})里某只怪的攻击目标变成她,发一次 {@code targeted}。盯着期间不再发,
 *       抖动去抖,见 {@link TargetingWatch};</li>
 *   <li><b>没了</b>:她留意的对象被移出世界,发一次 {@code gone},附原版的移除原因。走出半径不发——它还在,只是不在身边。</li>
 * </ul>
 *
 * <h2>她留意谁</h2>
 * 最近在她的事件里出现过的对象(打她的、盯上她的,{@link #follow})和她正在打、在用、骑着的({@code CompanionTickDispatcher#engagedBy})。
 * 发过 {@code gone} 就不再留意;留意的最多 {@value #FOCUS_CAP} 个,多了先放下最久没提起的。玩家不在其内:他们下线、被打倒都不是
 * 被移出世界。
 */
public final class Vigil {

    /** 留意的对象至多这么多:远处还活着的永远等不到被移出世界,不设上限就只增不减。 */
    static final int FOCUS_CAP = 16;

    private final TargetingWatch<UUID> targeting = new TargetingWatch<>();
    /** 留意的对象,按最近一次提起排;持有实体本身,因为移除原因只在被移出的那个对象上。 */
    private final Map<UUID, Entity> focus = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Entity> eldest) {
            return size() > FOCUS_CAP;
        }
    };

    /** 这个对象从此留意:它被移出世界时说一声。 */
    public void follow(Entity entity) {
        if (!(entity instanceof Player)) {
            focus.put(entity.getUUID(), entity);
        }
    }

    /** 每服务端 tick 一次。 */
    public void poll(NumenPlayer her, long now) {
        CompanionTickDispatcher.engagedBy(her).forEach(this::follow);

        List<Mob> hunters = NearbyEntities.within(her, NearbyEntities.RADIUS, Mob.class,
                mob -> mob.isAlive() && mob.getTarget() == her);
        Map<UUID, Mob> byId = new LinkedHashMap<>();
        hunters.forEach(mob -> byId.put(mob.getUUID(), mob));
        for (UUID id : targeting.poll(byId.keySet(), now)) {
            Mob mob = byId.get(id);
            NumenEvents.targeted(her, mob);
            follow(mob);
        }

        List<Entity> gone = new ArrayList<>();
        for (Iterator<Entity> it = focus.values().iterator(); it.hasNext(); ) {
            Entity entity = it.next();
            if (entity.isRemoved()) {
                gone.add(entity);
                it.remove();
            }
        }
        gone.forEach(entity -> NumenEvents.gone(her, entity, killerOf(entity)));
    }

    /** 倒下的是谁打倒的(原版的击杀归属);不是被打倒的、查不到的是 null。 */
    private static LivingEntity killerOf(Entity entity) {
        return entity instanceof LivingEntity living ? living.getKillCredit() : null;
    }
}
