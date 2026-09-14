package com.dwinovo.numen.permission;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一条规则:一行字符串 {@code 动作(项 & 项 & !项)},与 Claude Code 的 {@code Tool(specifier)}
 * 同形。动词是 {@link Action.Kind#verb} 或 {@code *};项是信号名、方块/实体种类 id
 * ({@code minecraft:chest})、标签({@code #minecraft:beds})、某一只实体({@code entity:<uuid>})
 * 或 {@code *};{@code !} 取反。全仓只在这一个类里解析。
 */
public final class Rule {

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
            throw new IllegalArgumentException("rule must look like verb(term & term): '" + raw + "'");
        }
        String verb = text.substring(0, open).trim();
        Action.Kind kind = null;
        if (!verb.equals("*")) {
            kind = Action.Kind.byVerb(verb);
            if (kind == null) {
                throw new IllegalArgumentException("unknown verb '" + verb + "' in rule '" + raw + "'");
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

        private Term(Type type, boolean negated, Signals signal, ResourceLocation id, UUID uuid) {
            this.type = type;
            this.negated = negated;
            this.signal = signal;
            this.id = id;
            this.uuid = uuid;
        }

        static Term parse(String raw, String rule) {
            boolean negated = raw.startsWith("!");
            String body = negated ? raw.substring(1).trim() : raw;
            if (body.isEmpty()) {
                throw new IllegalArgumentException("empty term in rule '" + rule + "'");
            }
            if (body.equals("*")) {
                return new Term(Type.ANY, negated, null, null, null);
            }
            if (body.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(body.substring(1));
                if (id == null) {
                    throw new IllegalArgumentException("bad tag '" + body + "' in rule '" + rule + "'");
                }
                return new Term(Type.TAG, negated, null, id, null);
            }
            if (body.startsWith("entity:")) {
                try {
                    return new Term(Type.ENTITY, negated, null, null, UUID.fromString(body.substring(7)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("bad entity uuid '" + body + "' in rule '" + rule + "'");
                }
            }
            if (body.contains(":")) {
                ResourceLocation id = ResourceLocation.tryParse(body);
                if (id == null) {
                    throw new IllegalArgumentException("bad id '" + body + "' in rule '" + rule + "'");
                }
                return new Term(Type.ID, negated, null, id, null);
            }
            Signals signal = Signals.byName(body);
            if (signal == null) {
                throw new IllegalArgumentException("unknown signal '" + body + "' in rule '" + rule + "'");
            }
            return new Term(Type.SIGNAL, negated, signal, null, null);
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
            Block block = subjectBlock(a);
            if (block != null) {
                return id.equals(BuiltInRegistries.BLOCK.getKey(block));
            }
            if (a.entity() != null) {
                return id.equals(EntityType.getKey(a.entity().getType()));
            }
            return a.item() != null && id.equals(BuiltInRegistries.ITEM.getKey(a.item()));
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
