package com.dwinovo.numen.permission;

import net.minecraft.world.Container;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;

/**
 * 给动作贴事实的函数,每个只回答一个通用问题、各自独立、无状态。规则文本里的信号名在
 * {@link #byName} 解析;一个封闭的集合,没有运行期登记。
 *
 * <p>不按方块或生物种类枚举:玩家放置、带方块实体、有主人、有名字、是村民、领地裁决,这几个
 * 信号覆盖原版和任何模组——高级工作台有方块实体,模组宠物继承原版驯服,都不用适配。
 *
 * <p>线程:每个信号只读 {@link Facts#view} 与 {@link Facts#placed}(任何线程可读);要活读世界
 * 的({@link #CONTENTS}、{@link #CLAIMED})只在 {@link Facts#live} 非空时读,否则按各自说明的
 * 保守值回答。
 */
public enum Signals {

    PLACED("placed", "placed by a player") {
        @Override
        boolean test(Action a, Facts f) {
            return a.pos() != null && f.placed() != null
                    && f.placed().isPlaced(a.pos(), f.view().getBlockState(a.pos()));
        }
    },

    BLOCK_ENTITY("block_entity", "has a block entity") {
        @Override
        boolean test(Action a, Facts f) {
            return a.state() != null && a.state().hasBlockEntity();
        }
    },

    /**
     * 容器里有没有东西。只有主线程读得到方块实体;搜索线程按"有"回答——规划比执行保守,
     * 一条要等主人点头的路不会被规划成免费的。
     */
    CONTENTS("contents", "has contents") {
        @Override
        boolean test(Action a, Facts f) {
            return a.pos() != null && a.state() != null && a.state().hasBlockEntity()
                    && (f.live() == null || hasContents(f.live().getBlockEntity(a.pos())));
        }
    },

    OWNED("owned", "has an owner") {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() instanceof OwnableEntity o && o.getOwnerUUID() != null;
        }
    },

    NAMED("named", "has a name") {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() != null && a.entity().hasCustomName();
        }
    },

    VILLAGER("villager", "is a villager") {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() instanceof AbstractVillager;
        }
    },

    HOSTILE("hostile", "is hostile") {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() instanceof Enemy;
        }
    },

    HAZARD_ITEM("hazard_item", "is a hazard") {
        @Override
        boolean test(Action a, Facts f) {
            return a.item() != null && HAZARD_ITEMS.contains(a.item());
        }
    },

    NEAR_PLACED("near_placed", "next to player-placed blocks") {
        @Override
        boolean test(Action a, Facts f) {
            return a.pos() != null && f.placed() != null
                    && f.placed().anyPlacedWithin(a.pos(), NEAR_PLACED_RADIUS, f.view());
        }
    },

    /** 领地 mod 说不。命中即 deny,不进规则表;主线程才问得到,搜索线程按"不知道"放行。 */
    CLAIMED("claimed", "inside someone's claim") {
        @Override
        boolean test(Action a, Facts f) {
            return f.live() != null && f.claims().forbids(a, f);
        }
    };

    /** {@code near_placed} 的邻域:放置点周围这么多格(切比雪夫距离)内有玩家放的方块就算。 */
    public static final int NEAR_PLACED_RADIUS = 3;

    /** 放下去会烧、会炸、会淹的东西。 */
    private static final Set<Item> HAZARD_ITEMS = Set.of(
            Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.TNT, Items.WATER_BUCKET);

    private final String ruleName;
    private final String description;

    Signals(String ruleName, String description) {
        this.ruleName = ruleName;
        this.description = description;
    }

    /** 规则文本里写的名字。 */
    public String ruleName() {
        return ruleName;
    }

    /** 命中时给回执用的自述("placed by a player")。 */
    public String description() {
        return description;
    }

    abstract boolean test(Action action, Facts facts);

    /** 按规则文本里的名字取信号;没有这个名字返回 null(规则解析据此报错)。 */
    public static Signals byName(String name) {
        for (Signals s : values()) {
            if (s.ruleName.equals(name)) {
                return s;
            }
        }
        return null;
    }

    private static boolean hasContents(BlockEntity be) {
        return be instanceof Container c && !c.isEmpty();
    }
}
