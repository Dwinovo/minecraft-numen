package com.dwinovo.numen.permission;

import com.dwinovo.numen.data.ModLanguageData;

import net.minecraft.network.chat.Component;
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
 * <p>不按方块或生物种类枚举:玩家放置、带方块实体、有主人、有名字、是村民,这几个信号覆盖原版和
 * 任何模组——高级工作台有方块实体,模组宠物继承原版驯服,都不用适配。
 *
 * <p>信号只陈述事实,不裁决,只在规则行里起作用。
 *
 * <p>线程:每个信号只读 {@link Facts#view} 与 {@link Facts#placed}(任何线程可读);要活读世界
 * 的({@link #CONTENTS})只在 {@link Facts#live} 非空时读,否则按它说明的保守值回答。
 */
public enum Signals {

    /**
     * 别人放的:这一格有放置记号,而且放的不是要动手的这只同伴自己——她自己垫的路、搭的桥是她的,
     * 主人、别的玩家、别人家同伴放的都算。
     */
    PLACED("placed", "placed by a player", false) {
        @Override
        boolean test(Action a, Facts f) {
            return placedByOther(a, f) != null;
        }

        /** 主人看到的是谁放的:"dwinovo 放的";不知道是谁放的(旧存档)照说"玩家放的"。 */
        @Override
        Component shown(Action a, Facts f) {
            PlacedBlocks.Placer placer = placedByOther(a, f);
            return placer != null && placer.known()
                    ? Component.translatable(ModLanguageData.Keys.PERMISSION_PLACED_BY, placer.name())
                    : super.shown(a, f);
        }
    },

    BLOCK_ENTITY("block_entity", "has a block entity", false) {
        @Override
        boolean test(Action a, Facts f) {
            return a.state() != null && a.state().hasBlockEntity();
        }
    },

    /**
     * 容器里有没有东西。只有主线程读得到方块实体;搜索线程按"有"回答——规划比执行保守,
     * 一条要等主人点头的路不会被规划成免费的。拆了东西洒一地会消失,撤不回。
     */
    CONTENTS("contents", "has contents", true) {
        @Override
        boolean test(Action a, Facts f) {
            return a.pos() != null && a.state() != null && a.state().hasBlockEntity()
                    && (f.live() == null || hasContents(f.live().getBlockEntity(a.pos())));
        }
    },

    /** 有主人的实体:打死了就是主人的宠物没了,撤不回。 */
    OWNED("owned", "has an owner", true) {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() instanceof OwnableEntity o && o.getOwnerUUID() != null;
        }
    },

    NAMED("named", "has a name", false) {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() != null && a.entity().hasCustomName();
        }
    },

    VILLAGER("villager", "is a villager", false) {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() instanceof AbstractVillager;
        }
    },

    HOSTILE("hostile", "is hostile", false) {
        @Override
        boolean test(Action a, Facts f) {
            return a.entity() instanceof Enemy;
        }
    },

    HAZARD_ITEM("hazard_item", "is a hazard", false) {
        @Override
        boolean test(Action a, Facts f) {
            return a.item() != null && HAZARD_ITEMS.contains(a.item());
        }
    },

    NEAR_PLACED("near_placed", "next to player-placed blocks", false) {
        @Override
        boolean test(Action a, Facts f) {
            return a.pos() != null && f.placed() != null
                    && f.placed().anyPlacedWithin(a.pos(), NEAR_PLACED_RADIUS, f.view(), actorId(f));
        }
    };

    /** {@code near_placed} 的邻域:放置点周围这么多格(切比雪夫距离)内有玩家放的方块就算。 */
    public static final int NEAR_PLACED_RADIUS = 3;

    /** 放下去会烧、会炸、会淹的东西。 */
    private static final Set<Item> HAZARD_ITEMS = Set.of(
            Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.TNT, Items.WATER_BUCKET);

    private final String ruleName;
    private final String description;
    private final boolean irreversible;

    Signals(String ruleName, String description, boolean irreversible) {
        this.ruleName = ruleName;
        this.description = description;
        this.irreversible = irreversible;
    }

    /** 规则文本里写的名字。 */
    public String ruleName() {
        return ruleName;
    }

    /** 命中时给回执用的自述("placed by a player")。 */
    public String description() {
        return description;
    }

    /**
     * 同一句自述给主人看的那一版,主人的客户端按自己的语言显示("玩家放的")。按这个动作与事实说,
     * 说得出具体的就说具体的(谁放的)。
     */
    Component shown(Action a, Facts f) {
        return Component.translatable(ModLanguageData.Keys.PERMISSION_SIGNAL_PREFIX + ruleName);
    }

    /**
     * 这个事实成立时动作撤不回(打死宠物、拆掉装着东西的容器)。它不改裁决——放行与拒绝只看规则行;
     * 它只让征询清单把这一条标出来,主人点头之前看得见。
     */
    public boolean irreversible() {
        return irreversible;
    }

    abstract boolean test(Action action, Facts facts);

    /** 这一格别人放的那一位;没有记号、或者就是要动手的同伴自己放的,为 null。 */
    private static PlacedBlocks.Placer placedByOther(Action a, Facts f) {
        if (a.pos() == null || f.placed() == null) {
            return null;
        }
        PlacedBlocks.Placer placer = f.placed().placerAt(a.pos(), f.view().getBlockState(a.pos()));
        return placer == null || placer.id().equals(actorId(f)) ? null : placer;
    }

    private static java.util.UUID actorId(Facts f) {
        return f.actor() == null ? null : f.actor().getUUID();
    }

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
