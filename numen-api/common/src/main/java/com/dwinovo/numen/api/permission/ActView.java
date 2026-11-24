package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.permission.Act;
import com.dwinovo.numen.permission.Atom;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verb;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

/**
 * 游戏里的一下动作,按规则核心的口({@link Act})回答:{@link Action} 说做什么、对什么,{@link Facts} 说世界此刻的样子。
 * 规则里的词自带的交给 {@link Groups},登记的交给登记它的一方({@link RuleWord},认的是 {@link Facts#words} 那一份);
 * 种类 id 与标签这里认,指令认 {@link Action.CommandLine#names}。登记的词拿到的就是这个对象:{@link #action} 与 {@link #facts}。
 */
public final class ActView implements Act {

    private final Action action;
    private final Facts facts;
    private Whose whose;
    private boolean whoseKnown;

    public ActView(Action action, Facts facts) {
        this.action = action;
        this.facts = facts;
    }

    /** 做什么、对哪一格哪只实体哪件物品。 */
    public Action action() {
        return action;
    }

    /** 读世界的口;在哪条线程上、读不读得到活世界,看 {@link Facts#live}。 */
    public Facts facts() {
        return facts;
    }

    /**
     * 动作的对象是谁的(归属,见 {@link Whose}):实体与格子上的方块都是问回答者问出来的,没人回答就是没主人的。
     * 丢东西与指令没有这样的对象,为 null。一次裁决里几行规则问好几遍,算一次。
     */
    Whose whose() {
        if (!whoseKnown) {
            whose = action.entity() != null ? Whose.ofEntity(action.entity(), facts)
                    : action.pos() != null ? Whose.ofBlock(action.pos(), facts) : null;
            whoseKnown = true;
        }
        return whose;
    }

    @Override
    public Verb verb() {
        return action.kind();
    }

    @Override
    public String actor() {
        return facts.actor().name();
    }

    /** 自带的词问 {@link Groups};登记的词问登记它的一方,登记撤回了的词在任何对象上都不成立。 */
    @Override
    public boolean is(String name, String arg) {
        Groups builtin = Groups.byName(name);
        if (builtin != null) {
            return builtin.test(this, arg);
        }
        RuleWord word = facts.words().get(name);
        return word != null && word.test().holds(this);
    }

    /** 挖/右键看格子上的方块,放看要放的方块,实体动作看实体种类,其余看物品。 */
    @Override
    public boolean ofKind(String id) {
        ResourceLocation kind = subjectId(action);
        return kind != null && kind.equals(ResourceLocation.parse(id));
    }

    @Override
    public boolean tagged(String tag) {
        ResourceLocation id = ResourceLocation.parse(tag);
        Block block = subjectBlock(action);
        if (block != null) {
            return BuiltInRegistries.BLOCK.wrapAsHolder(block).is(TagKey.create(Registries.BLOCK, id));
        }
        if (action.entity() != null) {
            return action.entity().getType().is(TagKey.create(Registries.ENTITY_TYPE, id));
        }
        return action.item() != null
                && BuiltInRegistries.ITEM.wrapAsHolder(action.item()).is(TagKey.create(Registries.ITEM, id));
    }

    @Override
    public boolean isEntity(String uuid) {
        return action.entity() != null && action.entity().getUUID().toString().equals(uuid);
    }

    @Override
    public boolean runs(String command) {
        return action.command() != null && action.command().names().contains(command);
    }

    @Override
    public String subject() {
        if (action.command() != null) {
            return action.command().root();
        }
        ResourceLocation id = subjectId(action);
        return id == null ? null : id.toString();
    }

    @Override
    public String entityKey() {
        return action.entity() == null ? null : action.entity().getUUID().toString();
    }

    /**
     * 这个动作命中 {@code rule} 的理由给主人看的那一版:按主人的语言显示,种类、标签与实体照原文。没有可说的就是规则原文。
     */
    Component shown(Rule rule) {
        MutableComponent out = null;
        for (Atom atom : rule.reasons(this)) {
            Component term = switch (atom.type()) {
                case NAMED -> shown(atom.name(), atom.arg());
                case COMMAND -> Component.literal("/" + atom.name());
                default -> Component.literal(atom.text());
            };
            out = out == null ? Component.empty().append(term)
                    : out.append(Component.translatable(ModLanguageData.Keys.LIST_SEPARATOR)).append(term);
        }
        return out == null ? Component.literal(rule.toString()) : out;
    }

    /** 一个在这个动作上成立的词给主人看的那一版:自带的按动作与事实说,登记的是它登记时给的那一句。 */
    private Component shown(String name, String arg) {
        Groups builtin = Groups.byName(name);
        return builtin != null ? builtin.shown(this, arg) : facts.words().get(name).shown();
    }

    /** 种类项认的那个 id:挖/右键看格子上的方块,放看要放的方块,实体动作看实体种类,其余看物品。 */
    private static ResourceLocation subjectId(Action a) {
        Block block = subjectBlock(a);
        if (block != null) {
            return BuiltInRegistries.BLOCK.getKey(block);
        }
        if (a.entity() != null) {
            return EntityType.getKey(a.entity().getType());
        }
        return a.item() == null ? null : BuiltInRegistries.ITEM.getKey(a.item());
    }

    private static Block subjectBlock(Action a) {
        return switch (a.kind()) {
            case BREAK, USE_BLOCK -> a.state() == null ? null : a.state().getBlock();
            case PLACE -> a.item() instanceof BlockItem bi ? bi.getBlock() : null;
            default -> null;
        };
    }
}
