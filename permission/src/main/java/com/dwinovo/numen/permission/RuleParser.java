package com.dwinovo.numen.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 规则括号里的对象条件的递归下降解析:
 * <pre>
 * or    := and ('|' and)*
 * and   := unary ('&amp;' unary)*
 * unary := '!' unary | '(' or ')' | term
 * term  := 词 | 词'('参数')'          参数紧贴在词后面,括号原样配对
 * </pre>
 * 词是不含空白与 {@code & | ! ( )} 的一截。项怎么认见 {@link Rule}。全仓只在这里解析,{@link Rule#parse} 调用它。
 */
final class RuleParser {

    /** 命名空间可选的 id:{@code minecraft:chest}、{@code chest}。只看写法。 */
    private static final Pattern ID = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private final String src;
    private final Verb verb;
    private final Vocabulary vocabulary;
    private final String rule;
    private int i;

    RuleParser(String src, Verb verb, Vocabulary vocabulary, String rule) {
        this.src = src;
        this.verb = verb;
        this.vocabulary = vocabulary;
        this.rule = rule;
    }

    /** {@code text[open]} 是 '(':与它配对的 ')' 的位置;没有配上返回 -1。 */
    static int closing(String text, int open) {
        int depth = 0;
        for (int k = open; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return k;
            }
        }
        return -1;
    }

    Cond parse() {
        Cond cond = or();
        skipSpaces();
        if (i < src.length()) {
            throw new Rule.Mistake("syntax", "unexpected '" + src.charAt(i) + "' in rule '" + rule
                    + "'; terms are joined with & and |, grouped with ( ), negated with !", String.valueOf(src.charAt(i)), rule);
        }
        return cond;
    }

    private Cond or() {
        List<Cond> parts = new ArrayList<>();
        parts.add(and());
        while (accept('|')) {
            parts.add(and());
        }
        return Cond.or(parts);
    }

    private Cond and() {
        List<Cond> parts = new ArrayList<>();
        parts.add(unary());
        while (accept('&')) {
            parts.add(unary());
        }
        return Cond.and(parts);
    }

    private Cond unary() {
        skipSpaces();
        if (i >= src.length() || "&|)".indexOf(src.charAt(i)) >= 0) {
            throw new Rule.Mistake("empty_term", "empty term in rule '" + rule + "'", rule);
        }
        if (accept('!')) {
            return new Cond.Not(unary());
        }
        if (accept('(')) {
            Cond inner = or();
            if (!accept(')')) {
                throw new Rule.Mistake("syntax", "missing ) in rule '" + rule + "'", ")", rule);
            }
            return inner;
        }
        return new Cond.Leaf(term());
    }

    private Atom term() {
        int start = i;
        while (i < src.length() && !isBreak(src.charAt(i))) {
            i++;
        }
        String body = src.substring(start, i);
        if (body.equals("*")) {
            return Atom.any();
        }
        if (verb == Verb.COMMAND) {
            if (body.startsWith("/")) {
                throw new Rule.Mistake("command_name", "bad command name '" + body + "' in rule '" + rule
                        + "'; write the command's root name without the slash, e.g. command(setblock)", body, rule);
            }
            return Atom.command(body);
        }
        if (body.startsWith("#")) {
            String id = body.substring(1);
            if (!ID.matcher(id).matches()) {
                throw new Rule.Mistake("tag", "bad tag '" + body + "' in rule '" + rule + "'", body, rule);
            }
            return Atom.tag(id);
        }
        if (body.startsWith("entity:")) {
            try {
                return Atom.entity(UUID.fromString(body.substring(7)).toString());
            } catch (IllegalArgumentException e) {
                throw new Rule.Mistake("uuid", "bad entity uuid '" + body + "' in rule '" + rule + "'", body, rule);
            }
        }
        if (body.contains(":")) {
            if (!ID.matcher(body).matches()) {
                throw new Rule.Mistake("id", "bad id '" + body + "' in rule '" + rule + "'", body, rule);
            }
            return Atom.id(body);
        }
        return named(body);
    }

    private Atom named(String name) {
        Vocabulary.Entry entry = vocabulary.get(name);
        if (entry == null) {
            String names = vocabulary.listing();
            throw new Rule.Mistake("name", "unknown name '" + name + "' in rule '" + rule + "'; groups and properties are "
                    + names + ", or write a namespaced id (minecraft:chest), a tag (#minecraft:beds), entity:<uuid> or *",
                    name, rule, names);
        }
        String arg = null;
        if (i < src.length() && src.charAt(i) == '(') {
            int close = closing(src, i);
            if (close < 0) {
                throw new Rule.Mistake("syntax", "missing ) in rule '" + rule + "'", ")", rule);
            }
            arg = src.substring(i + 1, close).trim();
            i = close + 1;
        }
        if (!entry.takesArgument() && arg != null) {
            throw new Rule.Mistake("argument_unexpected", "'" + name + "' takes no argument in rule '" + rule + "'",
                    name, rule);
        }
        if (entry.takesArgument()) {
            Vocabulary.Argument want = entry.argument();
            if (arg == null && want.required()) {
                throw new Rule.Mistake("argument_missing", "'" + name + "' needs an argument like " + name + "("
                        + want.hint() + ") in rule '" + rule + "'", name, rule, want.hint());
            }
            if (arg != null && !want.accepts().test(arg)) {
                throw new Rule.Mistake("argument_bad", "bad argument '" + arg + "' for '" + name + "' in rule '" + rule
                        + "'; write it like " + name + "(" + want.hint() + ")", arg, name, rule, want.hint());
            }
        }
        return Atom.named(entry, arg);
    }

    private boolean accept(char c) {
        skipSpaces();
        if (i < src.length() && src.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void skipSpaces() {
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
    }

    private static boolean isBreak(char c) {
        return Character.isWhitespace(c) || "&|!()".indexOf(c) >= 0;
    }
}
