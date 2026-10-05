package com.dwinovo.numen.core.act;

import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.LuaCodecs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 她这一挖的掉落物去了哪。一本账从她开挖记到收工:她的手每挖掉一格({@code ServerPlayerGameMode.destroyBlock},秒破、累着进度
 * 挖碎、服务端延迟落地的都走这里),那一格这一刻生成的掉落物({@code ServerLevel.addFreshEntity})记进她开着的这本账——掉在
 * 被挖的那一格的、连带碎掉的(墙上的火把、箱子里的东西)都算,别人挖的、她别的时候挖的不算。
 *
 * <p>每件记下的掉落物有一个去向,在它落定那一刻定下,之后世界里再发生的事不归这本账:
 * <ul>
 *   <li>落地:着地、水平几乎不动了——用的是原版掉落物自己判"不在动"的那条线({@code ItemEntity.tick} 里水平速度平方
 *       {@value #STILL});</li>
 *   <li>被捡起:原版每一次捡({@code LivingEntity.take},她、别的玩家、会捡东西的生物)说是谁;</li>
 *   <li>被毁:原版掉落物挨打到没血就没了({@code ItemEntity.hurt}),说是什么打的(岩浆、火、仙人掌、爆炸……);</li>
 *   <li>掉进虚空:原版的线,低于世界底部 64 格就没了({@code Entity.checkBelowWorld});</li>
 *   <li>不见了:别的路子收走了(漏斗之类),说在哪不见的;</li>
 *   <li>还在动:收工时还没落定(漂在水里、还在往深处掉),说此刻在哪。</li>
 * </ul>
 * 挨在一起的同种掉落物原版会并成一堆({@code ItemEntity.merge}):并过去的件数跟着去那一堆,账照记。
 *
 * <p>去向只在这里判,回执那句话({@link #sentence})与交给程序的数据({@link #data})从同一份记录写出。
 */
public final class Drops {

    /**
     * 收工前最多等掉落物落定这么多刻(2 秒)。原版的掉落物从被挖那一格中间弹起(竖直初速 0.2、每刻重力 0.04、阻力 0.98),落到那一格
     * 底下约 10 刻,在地上滑到停约再 7 刻(地面摩擦 0.6×0.98);进了岩浆每刻掉 4 点血、5 点血两刻就没;火和仙人掌每刻 1 点、5 刻;
     * 40 刻里自由落体能掉二十来格,一条矿道、一个小坑都够。漂在水里的永远不会着地,到这里就不再等,照它此刻在哪说。
     */
    public static final int SETTLE_TICKS = 40;

    /** 原版掉落物判"着了地不在动"的水平速度平方上限({@code ItemEntity.tick})。 */
    private static final double STILL = 1.0E-5;

    /** 去向;数据里写成小写的名字。 */
    public enum Fate { LANDED, PICKED_UP, DESTROYED, VOID, GONE, MOVING }

    /**
     * 一笔去向。
     *
     * @param item  物品 id
     * @param pos   落在哪一格、在哪被毁或被捡、此刻在哪
     * @param by    被谁捡起({@link Fate#PICKED_UP}):她自己是 {@code "you"},玩家是名字,生物是实体类型 id;其余为 null
     * @param cause 被什么毁的伤害类型 id({@link Fate#DESTROYED});其余为 null
     * @param fire  毁它的是火一类的伤害(原版标签 {@code minecraft:is_fire}:岩浆、火、营火、岩浆块……)
     * @param id    还在世界里的那一件的实体编号({@link Fate#LANDED}、{@link Fate#MOVING});其余为 null
     */
    public record Record(String item, int count, Fate fate, BlockPos pos, String by, String cause, boolean fire,
                         Integer id) {}

    // ---- 认领:原版的几处挂点交来(都在服务端主线程) ----

    /** 正在挖掉一格的玩家,按 destroyBlock 的嵌套。 */
    private static final Deque<ServerPlayer> BREAKING = new ArrayDeque<>();
    /** 开着账的身体。 */
    private static final Map<ServerPlayer, Drops> OPEN = new IdentityHashMap<>();
    /** 记着账、还没落定的掉落物。 */
    private static final Map<ItemEntity, Track> TRACKED = new IdentityHashMap<>();

    /** {@code destroyBlock} 开始:{@code player} 的手正在挖掉一格。 */
    public static void breakStarts(ServerPlayer player) {
        BREAKING.push(player);
    }

    /** {@code destroyBlock} 结束。 */
    public static void breakEnds() {
        BREAKING.pop();
    }

    /** 一只实体进了世界:正挖着一格、开着账的那具身体,掉落物记进她的账。 */
    public static void added(Entity entity) {
        if (entity instanceof ItemEntity item && !BREAKING.isEmpty()) {
            Drops drops = OPEN.get(BREAKING.peek());
            if (drops != null) {
                drops.claim(item, item.getItem().getCount());
            }
        }
    }

    /** 原版一次捡:{@code taker} 正把 {@code entity} 收进去,件数随后从那一堆里少掉。 */
    public static void taken(Entity entity, LivingEntity taker) {
        Track track = TRACKED.get(entity);
        if (track != null) {
            track.takenBy = taker;
        }
    }

    /** 并堆:{@code from} 那一堆里 {@code moved} 件并进了 {@code into},记着账的件数跟过去。 */
    public static void merged(ItemEntity into, ItemEntity from, int moved) {
        Track source = TRACKED.get(from);
        Track target = TRACKED.get(into);
        if (source != null && moved > 0) {
            int ours = Math.min(source.ours, moved);
            source.ours -= ours;
            source.seen = from.getItem().getCount();
            if (target == null && ours > 0) {
                target = source.drops.claim(into, 0);
            }
            if (target != null) {
                target.ours += ours;
            }
        }
        if (target != null) {
            target.seen = into.getItem().getCount();
        }
    }

    /** 掉落物挨了一下打;打没了就记下是什么打的。 */
    public static void hurt(ItemEntity item, DamageSource source) {
        Track track = TRACKED.get(item);
        if (track != null && item.isRemoved()) {
            track.destroyedBy = source;
        }
    }

    // ---- 一本账 ----

    /** 一件记着账、还没落定的掉落物。 */
    private static final class Track {
        final Drops drops;
        final ItemEntity entity;
        final String item;
        /** 这一堆里有几件是这本账的。 */
        int ours;
        /** 上一次看到的这一堆的件数。 */
        int seen;
        /** 这一刻正把它收进去的;没有为 null。 */
        LivingEntity takenBy;
        /** 把它打没了的;没有为 null。 */
        DamageSource destroyedBy;

        Track(Drops drops, ItemEntity entity, int ours) {
            this.drops = drops;
            this.entity = entity;
            this.item = BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).toString();
            this.ours = ours;
            this.seen = entity.getItem().getCount();
        }
    }

    private final ServerPlayer her;
    private final List<Track> live = new ArrayList<>();
    private final List<Record> records = new ArrayList<>();
    private boolean closed;

    private Drops(ServerPlayer her) {
        this.her = her;
    }

    /** 给 {@code her} 开一本账:从这一刻起她的手挖掉的格掉的东西记进来,直到 {@link #close}。 */
    public static Drops open(ServerPlayer her) {
        Drops drops = new Drops(her);
        OPEN.put(her, drops);
        return drops;
    }

    private Track claim(ItemEntity item, int ours) {
        Track track = new Track(this, item, ours);
        TRACKED.put(item, track);
        live.add(track);
        return track;
    }

    /** 记过东西没有。 */
    public boolean any() {
        return !live.isEmpty() || !records.isEmpty();
    }

    /** 记着账的都落定了。 */
    public boolean settled() {
        return live.isEmpty();
    }

    /** 每刻看一遍还没落定的:落定了的定下去向,不再跟。 */
    public void tick() {
        for (Iterator<Track> it = live.iterator(); it.hasNext(); ) {
            Track t = it.next();
            if (t.ours > 0 && settle(t)) {
                t.ours = 0;
            }
            if (t.ours == 0) {
                TRACKED.remove(t.entity);
                it.remove();
            }
        }
    }

    /** 看一件:定下了就记账、交回真。少掉的件数(被捡走一部分)当场记。 */
    private boolean settle(Track t) {
        ItemEntity e = t.entity;
        BlockPos at = e.blockPosition();
        if (e.isRemoved()) {
            if (e.getY() < e.level().getMinBuildHeight() - 64) {
                record(t, t.ours, Fate.VOID, at, null, null, null);
            } else if (t.takenBy != null) {
                record(t, t.ours, Fate.PICKED_UP, at, who(t.takenBy), null, null);
            } else if (t.destroyedBy != null) {
                records.add(new Record(t.item, t.ours, Fate.DESTROYED, at.immutable(), null,
                        t.destroyedBy.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse("unknown"),
                        t.destroyedBy.is(DamageTypeTags.IS_FIRE), null));
            } else {
                record(t, t.ours, Fate.GONE, at, null, null, null);
            }
            return true;
        }
        int now = e.getItem().getCount();
        if (now < t.seen) {
            int lost = Math.min(t.ours, t.seen - now);
            record(t, lost, t.takenBy != null ? Fate.PICKED_UP : Fate.GONE, at,
                    t.takenBy != null ? who(t.takenBy) : null, null, null);
            t.ours -= lost;
        }
        t.seen = now;
        t.takenBy = null;
        if (t.ours > 0 && e.onGround() && e.getDeltaMovement().horizontalDistanceSqr() <= STILL) {
            record(t, t.ours, Fate.LANDED, at, null, null, e.getId());
            return true;
        }
        return t.ours == 0;
    }

    private void record(Track t, int count, Fate fate, BlockPos pos, String by, String cause, Integer id) {
        if (count > 0) {
            records.add(new Record(t.item, count, fate, pos.immutable(), by, cause, false, id));
        }
    }

    /** 捡起它的是谁:她自己是 {@code "you"},玩家是名字,别的是实体类型 id。 */
    private String who(LivingEntity taker) {
        if (taker == her) {
            return "you";
        }
        return taker instanceof Player player ? player.getName().getString()
                : BuiltInRegistries.ENTITY_TYPE.getKey(taker.getType()).toString();
    }

    /** 收账:还没落定的照此刻在哪记成还在动,不再跟。可以重复调。 */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        tick();
        for (Track t : live) {
            record(t, t.ours, Fate.MOVING, t.entity.blockPosition(), null, null, t.entity.getId());
            TRACKED.remove(t.entity);
        }
        live.clear();
        OPEN.remove(her, this);
    }

    /** 交给程序的一笔去向。 */
    @Doc("Where some of what a dig dropped went.")
    public record Drop(@Doc("The item id, minecraft:cobblestone.") String item,
                       @Doc("How many.") int count,
                       @Doc("landed (lies on the ground), picked_up, destroyed (burned in lava or fire, broken on a "
                               + "cactus …), void (fell out of the world), gone (taken by something that is no creature, "
                               + "a hopper …) or moving (still falling or drifting when the dig stopped waiting).")
                       Fate fate,
                       @Doc("The cell it landed in, was destroyed or picked up in, or was in when the dig stopped "
                               + "waiting.") BlockPos pos,
                       @Doc("Who picked it up: you, a player's name, or a creature's type id.") Optional<String> by,
                       @Doc("What destroyed it: the damage type id, minecraft:lava.") Optional<String> cause,
                       @Doc("The entity id of the item still lying there (landed or moving), as numen.scan.entities "
                               + "gives it.") Optional<Integer> id) {}

    /** 交给程序的那一份:一笔一项。 */
    public List<Drop> data() {
        return records.stream().map(r -> new Drop(r.item(), r.count(), r.fate(), r.pos(), Optional.ofNullable(r.by()),
                Optional.ofNullable(r.cause()), Optional.ofNullable(r.id()))).toList();
    }

    /**
     * 回执里那一句,以 {@code "Drops: "} 起头、句号结尾:同一种去向、同一样东西、同一个经手的合成一段,写件数与在哪。一笔都没有是
     * 空串。
     */
    public String sentence() {
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        for (Record r : records) {
            groups.computeIfAbsent(java.util.Arrays.asList(r.fate(), r.item(), r.by(), r.cause()), k -> new Group(r))
                    .add(r);
        }
        if (groups.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(groups.size());
        for (Group g : groups.values()) {
            parts.add(g.words());
        }
        return "Drops: " + String.join("; ", parts) + ".";
    }

    /** 合成一段的几笔。 */
    private static final class Group {
        final Record first;
        int count;
        final Set<BlockPos> cells = new LinkedHashSet<>();

        Group(Record first) {
            this.first = first;
        }

        void add(Record r) {
            count += r.count();
            cells.add(r.pos());
        }

        String words() {
            String what = count + " " + first.item().substring(first.item().indexOf(':') + 1);
            return switch (first.fate()) {
                case LANDED -> what + " landed at " + where();
                case PICKED_UP -> Objects.equals(first.by(), "you") ? "I picked up " + what
                        : first.by() + " picked up " + what;
                case DESTROYED -> what + destroyed() + " at " + where();
                case VOID -> what + " fell into the void";
                case GONE -> what + " disappeared at " + where();
                case MOVING -> what + " was still moving at " + where() + " when I stopped watching";
            };
        }

        /** 被什么毁的,写成一句里的那一截。 */
        private String destroyed() {
            if ("minecraft:lava".equals(first.cause())) {
                return " fell into lava and burned up";
            }
            return first.fire() ? " burned up (" + first.cause() + ")" : " was destroyed (" + first.cause() + ")";
        }

        /** 在哪:一格写一格,几格写前三格与还有几处。 */
        private String where() {
            List<String> shown = new ArrayList<>();
            for (BlockPos cell : cells) {
                if (shown.size() == 3) {
                    break;
                }
                shown.add(LuaCodecs.literal(cell));
            }
            int more = cells.size() - shown.size();
            if (more > 0) {
                return String.join(", ", shown) + " and " + more + " more spot(s)";
            }
            if (shown.size() == 1) {
                return shown.get(0);
            }
            return String.join(", ", shown.subList(0, shown.size() - 1)) + " and " + shown.get(shown.size() - 1);
        }
    }
}
