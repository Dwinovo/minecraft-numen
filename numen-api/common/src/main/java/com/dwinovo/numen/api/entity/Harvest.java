package com.dwinovo.numen.api.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 她的手挖出来的东西当场进背包(同伴的是 {@link NumenPlayer#harvest})。她的手每挖碎一格——原版
 * {@code ServerPlayerGameMode.destroyBlock},秒破、累着进度挖碎、服务端延迟落地的都走这里——这一挖生成的掉落物(方块本身的、连带
 * 碎掉的火把、箱子里的东西)逐件走一次原版的拾取({@code ItemEntity.playerTouch}:拾取事件、统计、进度照常触发,往背包里放的是
 * 原版自己);放不下的留在原地,仍是一件普通的掉落物。经验球不在里面,照原版飞向她。
 *
 * <p>这是身体的事:谁驱动她的手都一样(挖掘的活、走路途中挖的、左键点一下)。本来就在地上的东西不归这里,走过去才捡。背包放不下
 * 时要说的那一声也不在这里:拾取那一下照常经 {@link NumenPlayer#touchedItem}。
 *
 * <p>干活的人要在回执里说"这一趟挖到了什么"时开一本账({@link #open}):从开到收,她的手挖出来的东西里进了背包的与留在原地的。
 *
 * <p>只在服务端主线程上用。
 */
public final class Harvest {

    /** 放不下、留在原地的一笔:什么东西、几个、留在哪一格。 */
    public record Left(Item item, int count, BlockPos pos) {}

    /** 一本账:从开到 {@link #close},她的手挖出来的东西各去了哪。 */
    public final class Ledger {

        private final Map<Item, Integer> taken = new LinkedHashMap<>();
        private final Map<Left, Integer> left = new LinkedHashMap<>();

        private Ledger() {}

        /** 进了背包的:什么东西、一共几个,按第一次挖到的先后。 */
        public Map<Item, Integer> taken() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(taken));
        }

        /** 放不下、留在原地的:同一格里的同一样东西合成一笔,按先后。 */
        public List<Left> left() {
            List<Left> out = new ArrayList<>(left.size());
            left.forEach((at, count) -> out.add(new Left(at.item(), count, at.pos())));
            return out;
        }

        /** 收账:之后挖出来的不再记。可以重复调。 */
        public void close() {
            ledgers.remove(this);
        }

        private void record(Item item, int took, int stayed, BlockPos pos) {
            if (took > 0) {
                taken.merge(item, took, Integer::sum);
            }
            if (stayed > 0) {
                // 键只认"什么东西、在哪一格",件数记在值上
                left.merge(new Left(item, 0, pos), stayed, Integer::sum);
            }
        }
    }

    private final NumenPlayer her;
    private final List<Ledger> ledgers = new ArrayList<>();

    Harvest(NumenPlayer her) {
        this.her = her;
    }

    /** 开一本账:从这一刻起她的手挖出来的东西记进来,直到 {@link Ledger#close}。 */
    public Ledger open() {
        Ledger ledger = new Ledger();
        ledgers.add(ledger);
        return ledger;
    }

    /**
     * 这一挖生成的掉落物逐件拾取。刚生成的掉落物带着拾取冷却,原版要等它走完才让人捡:先清掉再走原版的拾取,没捡完的(背包放不下)
     * 把冷却还成原版方块掉落物的那一段。
     */
    private void take(List<ItemEntity> drops) {
        for (ItemEntity drop : drops) {
            if (drop.isRemoved()) {
                continue;
            }
            Item item = drop.getItem().getItem();
            int before = drop.getItem().getCount();
            drop.setNoPickUpDelay();
            drop.playerTouch(her);
            // 整件捡走时原版把这一件移除、件数改回原样(给拾取动画用),所以先看它还在不在
            int stayed = drop.isRemoved() ? 0 : drop.getItem().getCount();
            if (stayed > 0) {
                drop.setDefaultPickUpDelay();
            }
            BlockPos pos = drop.blockPosition();
            for (Ledger ledger : ledgers) {
                ledger.record(item, before - stayed, stayed, pos);
            }
        }
    }

    // ==================== 平台挂点(都在服务端主线程) ====================
    // 下面三个入口是平台挂点:平台把"谁的手正在挖碎一格、这一挖生成了哪些掉落物"告诉这里,common 不知道是谁在调。
    // breakStarts/breakEnds 成对嵌套(destroyBlock 里可再触发 destroyBlock),breakEnds 在 breakStarts 之后无论挖成没成都要调。

    /** 正在挖碎一格的那几层,按 destroyBlock 的嵌套;不是同伴的那一层不收掉落物({@code drops} 为 null)。 */
    private record Breaking(NumenPlayer her, List<ItemEntity> drops) {}

    private static final Deque<Breaking> BREAKING = new ArrayDeque<>();

    /**
     * {@code destroyBlock} 开始:{@code player} 的手正在挖碎一格。<b>平台挂点。</b>模组上由 {@code ServerPlayerGameModeHarvestMixin}
     * 包住 {@code ServerPlayerGameMode.destroyBlock} 调。调用方保证:在这一挖生成任何掉落物之前调,每次对应一次 {@link #breakEnds}。
     */
    public static void breakStarts(ServerPlayer player) {
        BREAKING.push(player instanceof NumenPlayer her ? new Breaking(her, new ArrayList<>()) : new Breaking(null, null));
    }

    /**
     * 这一挖生成了一只实体:是掉落物、挖的是同伴,就记下等这一挖结束时拾取。<b>平台挂点。</b>模组上由 {@code ServerLevelHarvestMixin}
     * 在 {@code ServerLevel.addFreshEntity} 真把实体放进世界时调。
     * 调用方保证:在 {@link #breakStarts}/{@link #breakEnds} 之间才有意义,别的时候调什么也不做。
     */
    public static void added(Entity entity) {
        if (entity instanceof ItemEntity item && !BREAKING.isEmpty() && BREAKING.peek().drops() != null) {
            BREAKING.peek().drops().add(item);
        }
    }

    /**
     * {@code destroyBlock} 结束(成败都调):挖的是同伴,这一挖生成的掉落物当场进她背包。<b>平台挂点</b>,与 {@link #breakStarts}
     * 同一处调。
     */
    public static void breakEnds() {
        Breaking done = BREAKING.pop();
        if (done.her() != null) {
            done.her().harvest().take(done.drops());
        }
    }
}
