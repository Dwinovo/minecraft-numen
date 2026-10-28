package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.permission.Vocabulary;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原版的对象组与属性,规则行里写的名词。每个只回答一个通用问题、各自独立、无状态:
 * <ul>
 *   <li><b>归属</b>六类,互斥且完备({@link Whose}):{@code self}、{@code owner}、{@code other}、{@code structure}(可带结构类型
 *       标签,{@code structure(#minecraft:village)})、{@code nature}、{@code unknown};</li>
 *   <li><b>属性</b>:{@code block_entity}、{@code contents}、{@code named}、{@code villager}、{@code hostile}、{@code hazard_item}、
 *       {@code near(<组>)}(放置点附近有没有属于那一组的方块,组是 {@code self}、{@code owner}、{@code other})。</li>
 * </ul>
 * 规则文本里的名字登记在 {@link #VOCABULARY},规则核心解析时据此核对;一个名字对不对得上一个具体对象,由 {@link #test} 回答。
 *
 * <p>不按方块或生物种类枚举:谁放的、在不在结构里、带方块实体、有主人、有名字、是村民,这几个事实覆盖原版和任何模组——
 * 高级工作台有方块实体,模组宠物继承原版驯服,都不用适配。
 *
 * <p>只陈述事实,不裁决,只在规则行里起作用。线程:只读 {@link Facts#view}、{@link Facts#placed} 与 {@link Facts#structures}
 * (任何线程可读);要活读世界的({@link #CONTENTS})只在 {@link Facts#live} 非空时读,否则按它说明的保守值回答。
 */
public enum Groups {

    /** 她自己的:她放的方块,她驯服的狼与女仆。 */
    SELF("self", "belongs to herself", EnumSet.of(Verb.ATTACK)) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.SELF;
        }
    },

    /** 主人的:主人或主人名下别的同伴放的方块,他们的宠物,以及他们本人。 */
    OWNER("owner", "belongs to the owner", EnumSet.of(Verb.ATTACK)) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.OWNER;
        }

        /** 方块说是谁放的(主人看到的是"你的")。 */
        @Override
        Component shown(ActView v, String arg) {
            return Component.translatable(ModLanguageData.Keys.PERMISSION_NAME_PREFIX + ruleName);
        }
    },

    /** 别的玩家的:别人或别人名下同伴放的方块,别人的宠物与同伴。 */
    OTHER("other", "belongs to someone else", EnumSet.of(Verb.ATTACK)) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.OTHER;
        }

        /** 主人看到的是谁放的:"Steve 放的";记录说不出是谁就说"别人的"。 */
        @Override
        Component shown(ActView v, String arg) {
            PlacedBlocks.Placer placer = v.placer();
            return placer != null && placer.known()
                    ? Component.translatable(ModLanguageData.Keys.PERMISSION_PLACED_BY, placer.name())
                    : super.shown(v, arg);
        }
    },

    /**
     * 生成结构的:世界生成时记下的结构件范围里的方块。不带参数是任何结构;带结构类型标签({@code structure(#minecraft:village)})
     * 只认那一类。
     */
    STRUCTURE("structure", "part of a generated structure", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            if (v.whose() != Whose.STRUCTURE) {
                return false;
            }
            return arg == null || v.facts().structures().pieceAt(v.action().pos()).structure()
                    .is(TagKey.create(Registries.STRUCTURE, ResourceLocation.parse(arg.substring(1))));
        }
    },

    /** 没人的:地形自己长的,种类在自然方块表里;野生的、没主人的实体。 */
    NATURE("nature", "natural, nobody's", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.NATURE;
        }
    },

    /** 说不清的:不在记录里、不在结构里、也不是地形会长出来的方块——装模组前盖的木屋多半落在这里。 */
    UNKNOWN("unknown", "of unknown origin", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.UNKNOWN;
        }
    },

    BLOCK_ENTITY("block_entity", "has a block entity", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.action().state() != null && v.action().state().hasBlockEntity();
        }
    },

    /**
     * 容器里有没有东西。只有主线程读得到方块实体;搜索线程按"有"回答——规划比执行保守,
     * 一条要等主人点头的路不会被规划成免费的。拆了东西洒一地会消失,撤不回。
     */
    CONTENTS("contents", "has contents", EnumSet.allOf(Verb.class)) {
        @Override
        boolean test(ActView v, String arg) {
            Action a = v.action();
            return a.pos() != null && a.state() != null && a.state().hasBlockEntity()
                    && (v.facts().live() == null || hasContents(v.facts().live().getBlockEntity(a.pos())));
        }
    },

    NAMED("named", "has a name", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.action().entity() != null && v.action().entity().hasCustomName();
        }
    },

    VILLAGER("villager", "is a villager", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.action().entity() instanceof AbstractVillager;
        }
    },

    HOSTILE("hostile", "is hostile", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.action().entity() instanceof Enemy;
        }
    },

    HAZARD_ITEM("hazard_item", "is a hazard", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.action().item() != null && HAZARD_ITEMS.contains(v.action().item());
        }
    },

    /** 放置点附近有没有属于那一组的方块:{@code near(owner)}、{@code near(other)}、{@code near(self)},只看放置记录。 */
    NEAR("near", "next to blocks that belong to someone", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            Action a = v.action();
            Facts f = v.facts();
            if (a.pos() == null || f.placed() == null) {
                return false;
            }
            Whose group = NEAR_GROUPS.get(arg);
            return f.placed().anyPlacedWithin(a.pos(), NEAR_RADIUS, f.view(),
                    placer -> Whose.ofPlacer(placer, f.actor()) == group);
        }

        @Override
        Component shown(ActView v, String arg) {
            return Component.translatable(ModLanguageData.Keys.PERMISSION_NEAR,
                    Component.translatable(ModLanguageData.Keys.PERMISSION_NAME_PREFIX + arg));
        }
    };

    /** {@code near} 的邻域:放置点周围这么多格(切比雪夫距离)内有玩家放的方块就算。 */
    public static final int NEAR_RADIUS = 3;

    /** {@code near(<组>)} 认的三个归属;判断时直接查,不每次拼名字再解析枚举。 */
    private static final Map<String, Whose> NEAR_GROUPS =
            Map.of("self", Whose.SELF, "owner", Whose.OWNER, "other", Whose.OTHER);

    /** 按规则名查组的表;判断路径上每问一项都查,不能每次走 {@link #values()}(它每次新建一份数组)。 */
    private static final Map<String, Groups> BY_NAME = indexByName();

    /** 放下去会烧、会炸、会淹的东西。 */
    private static final Set<Item> HAZARD_ITEMS = Set.of(
            Items.LAVA_BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.TNT, Items.WATER_BUCKET);

    /** 规则里认得的名字:这里登记的全部组与属性。 */
    public static final Vocabulary VOCABULARY = vocabulary();

    final String ruleName;
    private final String description;
    private final Set<Verb> irreversibleOn;

    Groups(String ruleName, String description, Set<Verb> irreversibleOn) {
        this.ruleName = ruleName;
        this.description = description;
        this.irreversibleOn = irreversibleOn;
    }

    /** 规则文本里写的名字。 */
    public String ruleName() {
        return ruleName;
    }

    /**
     * 同一句自述给主人看的那一版,主人的客户端按自己的语言显示("你的")。按这个动作与事实说,
     * 说得出具体的就说具体的(谁放的)。
     */
    Component shown(ActView v, String arg) {
        return Component.translatable(ModLanguageData.Keys.PERMISSION_NAME_PREFIX + ruleName);
    }

    abstract boolean test(ActView view, String arg);

    /**
     * 实体的主人是谁:原版 {@link OwnableEntity} 记的主人 UUID(狼、猫、鹦鹉,以及继承原版驯服的模组宠物,比如车万女仆);
     * 没有主人或不是可驯服的实体为 null。"这只是谁的"只在这里读——对象组与扫描实体都问它。
     */
    public static UUID ownerOf(Entity entity) {
        return entity instanceof OwnableEntity o ? o.getOwnerUUID() : null;
    }

    /** 按规则文本里的名字取;没有这个名字返回 null。 */
    public static Groups byName(String name) {
        return BY_NAME.get(name);
    }

    private static Map<String, Groups> indexByName() {
        Map<String, Groups> out = new HashMap<>();
        for (Groups g : values()) {
            out.put(g.ruleName, g);
        }
        return Map.copyOf(out);
    }

    /** 用原版的名字表解析一行规则;写错了抛 {@link Rule.Mistake}。全仓解析规则文本都走这里。 */
    public static Rule parse(String text) {
        return Rule.parse(text, VOCABULARY);
    }

    private static Vocabulary vocabulary() {
        Vocabulary.Builder builder = Vocabulary.builder();
        for (Groups g : values()) {
            switch (g) {
                case STRUCTURE -> builder.namedWithOptionalArgument(g.ruleName, g.description, "#minecraft:village",
                        Groups::isTag);
                case NEAR -> builder.namedWithArgument(g.ruleName, g.description, "owner",
                        NEAR_GROUPS::containsKey);
                default -> {
                    if (g.irreversibleOn.isEmpty()) {
                        builder.name(g.ruleName, g.description);
                    } else {
                        builder.irreversibleName(g.ruleName, g.description, g.irreversibleOn);
                    }
                }
            }
        }
        return builder.build();
    }

    private static boolean isTag(String arg) {
        return arg.startsWith("#") && ResourceLocation.tryParse(arg.substring(1)) != null;
    }

    private static boolean hasContents(BlockEntity be) {
        return be instanceof Container c && !c.isEmpty();
    }
}
