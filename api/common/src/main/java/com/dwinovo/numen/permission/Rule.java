package com.dwinovo.numen.permission;

import com.dwinovo.numen.data.ModLanguageData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 一条规则:一行字符串 {@code 动作(项 & 项 & !项)},与 Claude Code 的 {@code Tool(specifier)}
 * 同形。动词是 {@link Action.Kind#verb} 或 {@code *};项是信号名、方块/实体种类 id
 * ({@code minecraft:chest})、标签({@code #minecraft:beds})、某一只实体({@code entity:<uuid>})
 * 或 {@code *};{@code !} 取反。全仓只在这一个类里解析。
 */
public final class Rule {

    /** 存档里的一行规则;写错的行解码失败,消息与 {@link #parse} 同一份。 */
    public static final Codec<Rule> CODEC = Codec.STRING.comapFlatMap(Rule::decode, Rule::toString);

    private final String text;
    private final Action.Kind kind;   // null = 任何动作
    private final List<Term> terms;

    private Rule(String text, Action.Kind kind, List<Term> terms) {
        this.text = text;
        this.kind = kind;
        this.terms = List.copyOf(terms);
    }

    /** 解析一行;写错了抛 {@link IllegalArgumentException},消息说清哪儿错。 */
    public static Rule parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        int open = text.indexOf('(');
        if (open <= 0 || !text.endsWith(")")) {
            throw new IllegalArgumentException(
                    "rule must look like verb(term & term), e.g. break(placed & !block_entity): '" + raw + "'");
        }
        String verb = text.substring(0, open).trim();
        Action.Kind kind = null;
        if (!verb.equals("*")) {
            kind = Action.Kind.byVerb(verb);
            if (kind == null) {
                throw new IllegalArgumentException("unknown verb '" + verb + "' in rule '" + raw + "'; verbs are "
                        + Arrays.stream(Action.Kind.values()).map(Action.Kind::verb).collect(Collectors.joining(", "))
                        + ", or * for any");
            }
        }
        String inner = text.substring(open + 1, text.length() - 1).trim();
        if (inner.isEmpty()) {
            throw new IllegalArgumentException("rule needs at least one term (use * for any): '" + raw + "'");
        }
        List<Term> terms = new ArrayList<>();
        for (String piece : inner.split("&")) {
            terms.add(Term.parse(piece.trim(), raw));
        }
        return new Rule(text, kind, terms);
    }

    private static DataResult<Rule> decode(String text) {
        try {
            return DataResult.success(parse(text));
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }

    public Action.Kind kind() {
        return kind;
    }

    /** 这条规则对这个动作成立吗。 */
    public boolean matches(Action action, Facts facts) {
        if (kind != null && kind != action.kind()) {
            return false;
        }
        for (Term t : terms) {
            if (!t.matches(action, facts)) {
                return false;
            }
        }
        return true;
    }

    /** 命中这条规则的动作撤不回:它的正项里有撤不回的信号({@link Signals#irreversible})。 */
    public boolean irreversible() {
        for (Term t : terms) {
            if (!t.negated && t.type == Term.Type.SIGNAL && t.signal.irreversible()) {
                return true;
            }
        }
        return false;
    }

    /** 命中时给回执的短语:各正项的自述,如 {@code placed by a player}、{@code is #minecraft:doors}。 */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Term t : terms) {
            if (!t.negated && !t.description().isEmpty()) {
                parts.add(t.description());
            }
        }
        return parts.isEmpty() ? text : String.join(", ", parts);
    }

    /** {@link #describe} 给主人看的那一版:信号按主人的语言显示,种类、标签与实体照原文。 */
    public Component shown() {
        MutableComponent out = null;
        for (Term t : terms) {
            if (t.negated || t.type == Term.Type.ANY) {
                continue;
            }
            out = out == null ? Component.empty().append(t.shown())
                    : out.append(Component.translatable(ModLanguageData.Keys.PERMISSION_SEPARATOR)).append(t.shown());
        }
        return out == null ? Component.literal(text) : out;
    }

    /**
     * 主人对一个问出来的动作说"允许并记住"时存下的那一行 allow。三样拼成:
     * <ol>
     *   <li>同一个动词;</li>
     *   <li>对象:实体认那一只({@code attack(named)} 某只狼 → {@code attack(entity:<uuid>)});方块与物品认种类
     *       id,并留着问出它的那一行的条件({@code break(placed)} 挖圆石 →
     *       {@code break(placed & minecraft:cobblestone)});</li>
     *   <li>撤不回的信号({@link Signals#irreversible})这一次不成立、不读活世界时却按成立算的,取反钉上
     *       ({@code break(block_entity)} 空箱子 → {@code break(block_entity & minecraft:chest & !contents)}):
     *       卡上这一条没标撤不回,记下的规则就盖不到撤不回的情形。</li>
     * </ol>
     * 那一行已经说到的信号不再添;记下的这一行一定盖得住这次问的动作。
     *
     * @param hit   这个动作命中的 ask 行;没有任何一行覆盖时为 null
     * @param facts 裁决这个动作用的那一份事实
     */
    static Rule remembering(Action action, Rule hit, Facts facts) {
        List<String> terms = new ArrayList<>();
        Set<Signals> mentioned = EnumSet.noneOf(Signals.class);
        boolean entity = action.entity() != null;
        if (entity) {
            terms.add("entity:" + action.entity().getUUID());
        }
        if (hit != null) {
            for (Term t : hit.terms) {
                if (t.type == Term.Type.SIGNAL) {
                    mentioned.add(t.signal);
                }
                if (!entity && t.type != Term.Type.ANY) {
                    terms.add(t.text);
                }
            }
        }
        ResourceLocation id = entity ? null : Term.subjectId(action);
        if (id != null && !terms.contains(id.toString())) {
            terms.add(id.toString());
        }
        Facts blind = new Facts(facts.view(), facts.placed(), null, facts.actor());
        for (Signals s : Signals.values()) {
            if (s.irreversible() && !mentioned.contains(s) && !s.test(action, facts) && s.test(action, blind)) {
                terms.add("!" + s.ruleName());
            }
        }
        return parse(action.kind().verb() + "(" + (terms.isEmpty() ? "*" : String.join(" & ", terms)) + ")");
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rule r && r.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    // ==================== 项 ====================

    private static final class Term {
        private enum Type { ANY, SIGNAL, TAG, ID, ENTITY }

        final Type type;
        final boolean negated;
        final Signals signal;
        final ResourceLocation id;
        final UUID uuid;
        /** 这一项的原文({@code !placed}、{@code minecraft:chest})。 */
        final String text;

        private Term(Type type, boolean negated, Signals signal, ResourceLocation id, UUID uuid, String body) {
            this.type = type;
            this.negated = negated;
            this.signal = signal;
            this.id = id;
            this.uuid = uuid;
            this.text = (negated ? "!" : "") + body;
        }

        static Term parse(String raw, String rule) {
            boolean negated = raw.startsWith("!");
            String body = negated ? raw.substring(1).trim() : raw;
            if (body.isEmpty()) {
                throw new IllegalArgumentException("empty term in rule '" + rule + "'");
            }
            if (body.equals("*")) {
                return new Term(Type.ANY, negated, null, null, null, body);
            }
            if (body.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(body.substring(1));
                if (id == null) {
                    throw new IllegalArgumentException("bad tag '" + body + "' in rule '" + rule + "'");
                }
                return new Term(Type.TAG, negated, null, id, null, body);
            }
            if (body.startsWith("entity:")) {
                try {
                    return new Term(Type.ENTITY, negated, null, null, UUID.fromString(body.substring(7)), body);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("bad entity uuid '" + body + "' in rule '" + rule + "'");
                }
            }
            if (body.contains(":")) {
                ResourceLocation id = ResourceLocation.tryParse(body);
                if (id == null) {
                    throw new IllegalArgumentException("bad id '" + body + "' in rule '" + rule + "'");
                }
                return new Term(Type.ID, negated, null, id, null, body);
            }
            Signals signal = Signals.byName(body);
            if (signal == null) {
                throw new IllegalArgumentException("unknown signal '" + body + "' in rule '" + rule + "'; signals are "
                        + Arrays.stream(Signals.values()).map(Signals::ruleName).collect(Collectors.joining(", "))
                        + ", or write a namespaced id (minecraft:chest), a tag (#minecraft:beds), entity:<uuid> or *");
            }
            return new Term(Type.SIGNAL, negated, signal, null, null, body);
        }

        boolean matches(Action action, Facts facts) {
            boolean hit = switch (type) {
                case ANY -> true;
                case SIGNAL -> signal.test(action, facts);
                case TAG -> tagHit(action);
                case ID -> idHit(action);
                case ENTITY -> action.entity() != null && uuid.equals(action.entity().getUUID());
            };
            return negated != hit;
        }

        String description() {
            return switch (type) {
                case ANY -> "";
                case SIGNAL -> signal.description();
                case TAG -> "is #" + id;
                case ID -> "is " + id;
                case ENTITY -> "is entity " + uuid;
            };
        }

        /** 给主人看的这一项;只对正项、非 {@code *} 调。 */
        Component shown() {
            return type == Type.SIGNAL ? signal.shown() : Component.literal(text);
        }

        /** 挖/右键看格子上的方块,放看要放的方块,实体动作看实体种类,拿/丢看物品。 */
        private boolean tagHit(Action a) {
            Block block = subjectBlock(a);
            if (block != null) {
                return BuiltInRegistries.BLOCK.wrapAsHolder(block).is(TagKey.create(Registries.BLOCK, id));
            }
            if (a.entity() != null) {
                return a.entity().getType().is(TagKey.create(Registries.ENTITY_TYPE, id));
            }
            return a.item() != null && BuiltInRegistries.ITEM.wrapAsHolder(a.item()).is(TagKey.create(Registries.ITEM, id));
        }

        private boolean idHit(Action a) {
            return id.equals(subjectId(a));
        }

        /** 种类项认的那个 id:挖/右键/拿看格子上的方块,放看要放的方块,实体动作看实体种类,其余看物品。 */
        static ResourceLocation subjectId(Action a) {
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
}
