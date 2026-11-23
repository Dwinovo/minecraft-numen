package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.permission.Vocabulary;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 规则行里写的名词。这里是 Numen API 自带的那几个,都不用认识任何具体的东西,每个只回答一个通用问题、各自独立、无状态:
 * <ul>
 *   <li><b>归属</b>六类,互斥且完备({@link Whose}):{@code self}、{@code owner}、{@code other}、{@code structure}(可带结构类型
 *       标签,{@code structure(#minecraft:village)})、{@code nature}、{@code unknown};</li>
 *   <li><b>属性</b>:{@code block_entity}、{@code named}、{@code near(<组>)}(放置点附近有没有属于那一组的方块,组是
 *       {@code self}、{@code owner}、{@code other})。</li>
 * </ul>
 * 说得出"这是哪一类东西"的词(村民、敌对生物、容器里有没有东西)不在这里:谁认得谁登记({@link RuleWord},经
 * {@link Questions#RULE_WORD}),原版的那几个由 Numen 登记。规则能用的词就是这两处合起来的:解析、教学式的错误、撤不回的判断都从
 * {@link #vocabulary} 取,全仓只此一处。
 *
 * <p>只陈述事实,不裁决,只在规则行里起作用。线程:只读 {@link Facts#view}、{@link Facts#placed} 与 {@link Facts#structures}
 * (任何线程可读)。
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

    NAMED("named", "has a name", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.action().entity() != null && v.action().entity().hasCustomName();
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
     *
     * <p>只有玩家做主人才算有主:主人是世界里一只活着的非玩家(唤魔者的恼鬼、召唤者的随从)时,它没有人的主人,按没主算。
     * 找不到这个主人(离线的玩家、没加载的区块)按人算——不知道是谁的就当是人的。
     */
    public static UUID ownerOf(Entity entity) {
        if (!(entity instanceof OwnableEntity o) || o.getOwnerUUID() == null) {
            return null;
        }
        UUID owner = o.getOwnerUUID();
        boolean mobOwner = entity.level() instanceof ServerLevel level
                && level.getEntity(owner) instanceof Entity found && !(found instanceof Player);
        return mobOwner ? null : owner;
    }

    /** 按规则文本里的名字取自带的那一个;不是自带的名字返回 null。 */
    public static Groups byName(String name) {
        return BY_NAME.get(name);
    }

    /** 自带的全部名字:登记的词不能用({@link Questions#RULE_WORD} 把它们占着)。 */
    public static Set<String> names() {
        return BY_NAME.keySet();
    }

    private static Map<String, Groups> indexByName() {
        Map<String, Groups> out = new HashMap<>();
        for (Groups g : values()) {
            out.put(g.ruleName, g);
        }
        return Map.copyOf(out);
    }

    /** 此刻登记着的词,名字到词,按登记先后的一份快照。 */
    public static Map<String, RuleWord> registered() {
        return Questions.RULE_WORD.answerers();
    }

    /**
     * 按此刻认得的词解析一行规则;写错了抛 {@link Rule.Mistake}——用了没人登记的词也是写错。全仓解析规则文本都走这里。
     */
    public static Rule parse(String text) {
        return Rule.parse(text, vocabulary(registered()));
    }

    /**
     * 规则里认得的名字:自带的在前,{@code registered} 里的按登记先后接在后面。解析、教学式的错误里列的名字、撤不回的判断读的都是它。
     *
     * @param registered 登记着的词的一份快照({@link #registered},或一次裁决用的那一份 {@link Facts#words})
     */
    public static Vocabulary vocabulary(Map<String, RuleWord> registered) {
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
        registered.forEach((name, word) -> builder.irreversibleName(name, word.description(), word.irreversibleOn()));
        return builder.build();
    }

    private static boolean isTag(String arg) {
        return arg.startsWith("#") && ResourceLocation.tryParse(arg.substring(1)) != null;
    }
}
