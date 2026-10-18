package com.dwinovo.numen.permission;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一条规则:一行字符串 {@code 动作(对象条件) [@同伴名]},与 Claude Code 的 {@code Tool(specifier)} 同形。动词是 {@link Verb#word}
 * 或 {@code *};对象条件由项用 {@code &}、{@code |}、{@code !} 与括号拼成(优先级 {@code !} 高于 {@code &} 高于 {@code |}):
 * 项是 {@link Vocabulary} 里登记的组或属性({@code owner}、{@code near(other)})、方块/实体种类 id({@code minecraft:chest})、
 * 标签({@code #minecraft:beds})、某一只实体({@code entity:<uuid>})或 {@code *}。全仓只在这个类里解析,
 * 写错了回教学式的错误({@link Mistake}),列出认得的动词、名字。
 *
 * <p>{@code command} 的项不一样:除了 {@code *},每一项都是指令的根名({@code command(msg)}、{@code command(!tp)}),
 * 认的是 {@link Act#runs}。组与属性说的是方块与实体,一条指令没有它们,所以指令规则里不写。
 *
 * <p>主体:行末的 {@code @同伴名} 把规则限在那一只同伴身上({@link Act#actor});不写就是主人名下的每一只。
 *
 * <p>规则的原文是规范写法(只在必要处加括号、项之间一个空格),同一个意思只有一种写法,可以直接比较。
 */
public final class Rule {

    private final Verb verb;      // null = 任何动作
    private final Cond cond;
    private final String companion;   // null = 主人名下的每一只
    private final String text;

    private Rule(Verb verb, Cond cond, String companion) {
        this.verb = verb;
        this.cond = cond;
        this.companion = companion;
        this.text = (verb == null ? "*" : verb.word()) + "(" + cond.print() + ")"
                + (companion == null ? "" : " @" + companion);
    }

    /**
     * 规则写错了:原因码 + 现场的原话。{@link #getMessage()} 是给日志与脚本的英文诊断;给主人看的话由命令按码翻
     * ({@code numen.command.rule_mistake.<码>}),原话({@link #args})按码约定的顺序填进去。
     */
    public static final class Mistake extends IllegalArgumentException {
        private final String code;
        private final List<String> args;

        Mistake(String code, String message, String... args) {
            super(message);
            this.code = code;
            this.args = List.of(args);
        }

        public String code() {
            return code;
        }

        public List<String> args() {
            return args;
        }
    }

    /** 解析一行;写错了抛 {@link Mistake}(是 {@link IllegalArgumentException}),消息说清哪儿错。 */
    public static Rule parse(String raw, Vocabulary vocabulary) {
        String text = raw == null ? "" : raw.trim();
        int open = text.indexOf('(');
        int close = open <= 0 ? -1 : RuleParser.closing(text, open);
        if (close < 0) {
            throw new Mistake("shape",
                    "rule must look like verb(term & term), e.g. break(owner & !block_entity): '" + raw + "'", raw);
        }
        String companion = null;
        String rest = text.substring(close + 1).trim();
        if (!rest.isEmpty()) {
            companion = rest.startsWith("@") ? rest.substring(1).trim() : null;
            if (companion == null || companion.isEmpty()) {
                throw new Mistake("subject",
                        "after the closing ) a rule may only name one companion, like break(owner) @Aria: '" + raw + "'", raw);
            }
        }
        String word = text.substring(0, open).trim();
        Verb verb = null;
        if (!word.equals("*")) {
            verb = Verb.byWord(word);
            if (verb == null) {
                String verbs = Arrays.stream(Verb.values()).map(Verb::word).collect(Collectors.joining(", "));
                throw new Mistake("verb", "unknown verb '" + word + "' in rule '" + raw + "'; verbs are "
                        + verbs + ", or * for any", word, raw, verbs);
            }
        }
        String inner = text.substring(open + 1, close).trim();
        if (inner.isEmpty()) {
            throw new Mistake("empty", "rule needs at least one term (use * for any): '" + raw + "'", raw);
        }
        return new Rule(verb, new RuleParser(inner, verb, vocabulary, raw).parse(), companion);
    }

    /** 动词;{@code *} 是 null。 */
    public Verb verb() {
        return verb;
    }

    /** 规则限定的那一只同伴的名字;主人名下的每一只为 null。 */
    public String companion() {
        return companion;
    }

    /** 这条规则对这个动作成立吗。 */
    public boolean matches(Act act) {
        return (verb == null || verb == act.verb()) && (companion == null || companion.equals(act.actor()))
                && cond.matches(act);
    }

    /**
     * 这个动作命中这条规则的理由:条件里在这个动作上成立的、不在取反之下的项。{@code owner | other} 命中的是
     * 主人的方块,理由里就只有 {@code owner}。
     */
    public List<Atom> reasons(Act act) {
        List<Atom> out = new ArrayList<>();
        cond.reasons(act, out);
        return out;
    }

    /** 命中时给回执的短语:各理由的自述,如 {@code placed by a player}、{@code is #minecraft:beds}。 */
    public String describe(Act act) {
        String described = reasons(act).stream().map(Atom::description).filter(d -> !d.isEmpty())
                .collect(Collectors.joining(", "));
        return described.isEmpty() ? text : described;
    }

    /**
     * 主人对一个问出来的动作说"允许并记住"时存下的那一行 allow。三样拼成:
     * <ol>
     *   <li>同一个动词;</li>
     *   <li>对象:实体认那一只({@code attack(named)} 某只狼 → {@code attack(entity:<uuid>)});方块与物品认种类
     *       id,并留着问出它的那一行的条件({@code break(owner)} 挖圆石 → {@code break(owner & minecraft:cobblestone)});
     *       指令认她打的那个根名(没有规则说到的 {@code setblock …} → {@code command(setblock)});</li>
     *   <li>撤不回的名字({@link Vocabulary.Entry#irreversibleOn})这一次不成立、不读活世界时却按成立算的,取反钉上
     *       ({@code break(block_entity)} 空箱子 → {@code break(block_entity & minecraft:chest & !contents)}):
     *       卡上这一条没标撤不回,记下的规则就盖不到撤不回的情形。</li>
     * </ol>
     * 那一行已经说到的名字不再添;问出它的那一行限定了同伴,记下的也限定那一只。记下的这一行一定盖得住这次问的动作。
     *
     * @param now   这个动作此刻的对象(裁决用的那一份)
     * @param blind 同一个动作不读活世界时的对象——搜索线程按保守值回答的那一份
     * @param hit   这个动作命中的 ask 行;没有任何一行覆盖时为 null
     */
    public static Rule remembering(Act now, Act blind, Rule hit, Vocabulary vocabulary) {
        List<Cond> operands = new ArrayList<>();
        String entity = now.entityKey();
        if (entity != null) {
            operands.add(new Cond.Leaf(Atom.entity(entity)));
        } else if (hit != null) {
            for (Cond c : hit.cond.operands()) {
                if (!(c instanceof Cond.Leaf leaf && leaf.atom().type() == Atom.Type.ANY)) {
                    operands.add(c);
                }
            }
        }
        String subject = entity != null ? null : now.subject();
        if (subject != null) {
            Cond leaf = new Cond.Leaf(now.verb() == Verb.COMMAND ? Atom.command(subject) : Atom.id(subject));
            if (!operands.contains(leaf)) {
                operands.add(leaf);
            }
        }
        Set<String> mentioned = new LinkedHashSet<>();
        if (hit != null) {
            hit.cond.names(mentioned);
        }
        for (String name : vocabulary.irreversibleNames(now.verb())) {
            if (!mentioned.contains(name) && !now.is(name, null) && blind.is(name, null)) {
                operands.add(new Cond.Not(new Cond.Leaf(Atom.named(vocabulary.get(name), null))));
            }
        }
        return new Rule(now.verb(), operands.isEmpty() ? new Cond.Leaf(Atom.any()) : Cond.and(operands),
                hit == null ? null : hit.companion);
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
}
