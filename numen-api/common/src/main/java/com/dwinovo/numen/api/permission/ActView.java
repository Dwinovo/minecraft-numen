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
 * 组与属性交给 {@link Groups},种类 id 与标签这里认,指令认 {@link Action.CommandLine#names}。
 */
final class ActView implements Act {

    private final Action action;
    private final Facts facts;
    private Whose whose;
    private boolean whoseKnown;

    ActView(Action action, Facts facts) {
        this.action = action;
        this.facts = facts;
    }

    Action action() {
        return action;
    }

    Facts facts() {
        return facts;
    }

    /**
     * 动作的对象是谁的(归属,见 {@link Whose}):实体按驯服记录,格子上的方块按放置记录、结构、自然方块表。
     * 丢东西与指令没有这样的对象,为 null。一次裁决里几行规则问好几遍,算一次。
     */
    Whose whose() {
        if (!whoseKnown) {
            whose = action.entity() != null ? Whose.ofEntity(action.entity(), facts)
                    : action.pos() != null ? Whose.ofBlock(action, facts) : null;
            whoseKnown = true;
        }
        return whose;
    }

    /** 这一格上放置记号记的那一位;没有记号为 null。 */
    PlacedBlocks.Placer placer() {
        return action.pos() == null || facts.placed() == null ? null
                : facts.placed().placerAt(action.pos(), facts.view().getBlockState(action.pos()));
    }

    @Override
    public Verb verb() {
        return action.kind();
    }

    @Override
    public String actor() {
        return facts.actor().name();
    }

    @Override
    public boolean is(String name, String arg) {
        return Groups.byName(name).test(this, arg);
    }

    /** 挖/右键/拿看格子上的方块,放看要放的方块,实体动作看实体种类,其余看物品。 */
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
     * 这个动作命中 {@code rule} 的理由给主人看的那一版:按主人的语言显示(说得出谁放的就说谁),
     * 种类、标签与实体照原文。没有可说的就是规则原文。
     */
    Component shown(Rule rule) {
        MutableComponent out = null;
        for (Atom atom : rule.reasons(this)) {
            Component term = switch (atom.type()) {
                case NAMED -> Groups.byName(atom.name()).shown(this, atom.arg());
                case COMMAND -> Component.literal("/" + atom.name());
                default -> Component.literal(atom.text());
            };
            out = out == null ? Component.empty().append(term)
                    : out.append(Component.translatable(ModLanguageData.Keys.LIST_SEPARATOR)).append(term);
        }
        return out == null ? Component.literal(rule.toString()) : out;
    }

    /** 种类项认的那个 id:挖/右键/拿看格子上的方块,放看要放的方块,实体动作看实体种类,其余看物品。 */
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
            case BREAK, USE_BLOCK, TAKE -> a.state() == null ? null : a.state().getBlock();
            case PLACE -> a.item() instanceof BlockItem bi ? bi.getBlock() : null;
            default -> null;
        };
    }
}
