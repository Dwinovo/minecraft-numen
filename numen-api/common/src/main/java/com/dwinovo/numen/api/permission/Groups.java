package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Questions;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verb;
import com.dwinovo.numen.permission.Vocabulary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 规则行里写的名词。这里是 Numen API 自带的那几个,都不用认识任何具体的东西,每个只回答一个通用问题、各自独立、无状态:
 * <ul>
 *   <li><b>归属</b>四类,互斥且完备({@link Whose}):{@code self}、{@code owner}、{@code other}、{@code nature}。是谁的不在这里认,
 *       去问回答者({@link Owners});没人回答的就是 {@code nature};</li>
 *   <li><b>属性</b>:{@code structure}(这一格在某个生成结构件的范围里,可带结构类型标签,{@code structure(#minecraft:village)})、
 *       {@code block_entity}、{@code named}、{@code near(<组>)}(放置点附近有没有属于那一组的格,组是 {@code self}、
 *       {@code owner}、{@code other})。</li>
 * </ul>
 * 说得出"这是哪一类东西"的词(村民、敌对生物、容器里有没有东西)不在这里:谁认得谁登记({@link RuleWord},经
 * {@link Questions#RULE_WORD}),原版的那几个由 Numen 登记。规则能用的词就是这两处合起来的:解析、教学式的错误、撤不回的判断都从
 * {@link #vocabulary} 取,全仓只此一处。
 *
 * <p>{@code structure} 是自带的词,不交给谁登记:读区块里的结构起点是引擎的通用机制,和标签、方块实体同类,模组的结构也走同一张
 * 注册表,所以它只有这一个出处,没有第二个人答得了;它带参数,还要在主线程上先取一份快照({@link Structures}),登记的词两样都没有。
 * 它说的是格子在哪,和这一格是谁的各说各的:结构里的一格可以同时是某个人的。
 *
 * <p>只陈述事实,不裁决,只在规则行里起作用。线程:只读 {@link Facts#view}、{@link Facts#owners} 与 {@link Facts#structures}
 * (任何线程可读)。
 */
public enum Groups {

    /** 她自己的:回答者说是她的那些——她驯服的狼与女仆。 */
    SELF("self", "belongs to herself", EnumSet.of(Verb.ATTACK)) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.SELF;
        }
    },

    /** 主人这一边的:回答者说是主人或主人名下别的同伴的那些——他们的宠物、他们圈的地,以及他们本人。 */
    OWNER("owner", "belongs to the owner", EnumSet.of(Verb.ATTACK)) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.OWNER;
        }
    },

    /** 别人的:回答者说是别的玩家或别人名下同伴的那些——别人的宠物、别人圈的地,以及别的玩家与别人的同伴本人。 */
    OTHER("other", "belongs to someone else", EnumSet.of(Verb.ATTACK)) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.OTHER;
        }
    },

    /** 没主人的:没有哪个回答者说它是谁的——原版里的每一格方块,野生的实体。 */
    NATURE("nature", "nobody's", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            return v.whose() == Whose.NATURE;
        }
    },

    /**
     * 在生成结构里:这一格在世界生成时记下的某个结构件的范围里。不带参数是任何结构;带结构类型标签
     * ({@code structure(#minecraft:village)})只认那一类。
     */
    STRUCTURE("structure", "inside a generated structure", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            BlockPos pos = v.action().pos();
            Structures.Piece piece = pos == null ? null : v.facts().structures().pieceAt(pos);
            return piece != null && (arg == null
                    || piece.structure().is(TagKey.create(Registries.STRUCTURE, ResourceLocation.parse(arg.substring(1)))));
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

    /**
     * 放置点附近有没有属于那一组的格:{@code near(owner)}、{@code near(other)}、{@code near(self)},问的是方块归属那一份
     * ({@link Facts#owners})。没人回答方块归属时哪一组都不成立,不扫。
     */
    NEAR("near", "next to blocks that belong to someone", Set.of()) {
        @Override
        boolean test(ActView v, String arg) {
            BlockPos center = v.action().pos();
            Facts f = v.facts();
            if (center == null || f.owners().isEmpty()) {
                return false;
            }
            Whose group = NEAR_GROUPS.get(arg);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int dx = -NEAR_RADIUS; dx <= NEAR_RADIUS; dx++) {
                for (int dy = -NEAR_RADIUS; dy <= NEAR_RADIUS; dy++) {
                    for (int dz = -NEAR_RADIUS; dz <= NEAR_RADIUS; dz++) {
                        cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (Whose.ofBlock(cursor, f) == group) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override
        Component shown(ActView v, String arg) {
            return Component.translatable(ModLanguageData.Keys.PERMISSION_NEAR,
                    Component.translatable(ModLanguageData.Keys.PERMISSION_NAME_PREFIX + arg));
        }
    };

    /** {@code near} 的邻域:放置点周围这么多格(切比雪夫距离)内有那一组的格就算(含放置点自己)。 */
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

    /** 同一句自述给主人看的那一版,主人的客户端按自己的语言显示("你的")。 */
    Component shown(ActView v, String arg) {
        return Component.translatable(ModLanguageData.Keys.PERMISSION_NAME_PREFIX + ruleName);
    }

    abstract boolean test(ActView view, String arg);

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
