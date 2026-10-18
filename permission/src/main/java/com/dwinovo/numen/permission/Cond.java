package com.dwinovo.numen.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对象条件的语法树:{@code &}、{@code |}、{@code !} 与括号把 {@link Atom} 拼起来。优先级 {@code !} 高于 {@code &}
 * 高于 {@code |}。打印出来的是规范写法——只在必要处加括号,同一个条件只有一种写法,于是规则的原文可以直接比较。
 */
sealed interface Cond {

    boolean matches(Act act);

    /** 打印优先级:Or 1、And 2、Not 与叶子 3。 */
    int precedence();

    String print();

    /** 在这个动作上成立的、不在取反之下的项——命中规则时的"为什么"。 */
    void reasons(Act act, List<Atom> out);

    /** 条件里出现过的所有组与属性的名字,取反之下的也算。 */
    void names(Set<String> out);

    /** 顶层 {@code &} 的各项;不是 {@code &} 就是它自己。 */
    default List<Cond> operands() {
        return List.of(this);
    }

    static Cond and(List<Cond> parts) {
        List<Cond> flat = new ArrayList<>();
        for (Cond c : parts) {
            flat.addAll(c.operands());
        }
        return flat.size() == 1 ? flat.get(0) : new And(flat);
    }

    static Cond or(List<Cond> parts) {
        List<Cond> flat = new ArrayList<>();
        for (Cond c : parts) {
            if (c instanceof Or or) {
                flat.addAll(or.parts);
            } else {
                flat.add(c);
            }
        }
        return flat.size() == 1 ? flat.get(0) : new Or(flat);
    }

    private static String child(Cond c, int atLeast) {
        return c.precedence() < atLeast ? "(" + c.print() + ")" : c.print();
    }

    record Leaf(Atom atom) implements Cond {
        @Override
        public boolean matches(Act act) {
            return atom.matches(act);
        }

        @Override
        public int precedence() {
            return 3;
        }

        @Override
        public String print() {
            return atom.text();
        }

        @Override
        public void reasons(Act act, List<Atom> out) {
            if (atom.type() != Atom.Type.ANY && atom.matches(act)) {
                out.add(atom);
            }
        }

        @Override
        public void names(Set<String> out) {
            if (atom.type() == Atom.Type.NAMED) {
                out.add(atom.name());
            }
        }
    }

    record Not(Cond inner) implements Cond {
        @Override
        public boolean matches(Act act) {
            return !inner.matches(act);
        }

        @Override
        public int precedence() {
            return 3;
        }

        @Override
        public String print() {
            return "!" + child(inner, 3);
        }

        @Override
        public void reasons(Act act, List<Atom> out) {
            // 取反之下的项说的是"不是什么",不是命中的理由
        }

        @Override
        public void names(Set<String> out) {
            inner.names(out);
        }
    }

    record And(List<Cond> parts) implements Cond {
        public And {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean matches(Act act) {
            for (Cond c : parts) {
                if (!c.matches(act)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int precedence() {
            return 2;
        }

        @Override
        public String print() {
            return parts.stream().map(c -> child(c, 2)).collect(Collectors.joining(" & "));
        }

        @Override
        public void reasons(Act act, List<Atom> out) {
            parts.forEach(c -> c.reasons(act, out));
        }

        @Override
        public void names(Set<String> out) {
            parts.forEach(c -> c.names(out));
        }

        @Override
        public List<Cond> operands() {
            return parts;
        }
    }

    record Or(List<Cond> parts) implements Cond {
        public Or {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean matches(Act act) {
            for (Cond c : parts) {
                if (c.matches(act)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int precedence() {
            return 1;
        }

        @Override
        public String print() {
            return parts.stream().map(c -> child(c, 1)).collect(Collectors.joining(" | "));
        }

        @Override
        public void reasons(Act act, List<Atom> out) {
            for (Cond c : parts) {
                if (c.matches(act)) {
                    c.reasons(act, out);
                }
            }
        }

        @Override
        public void names(Set<String> out) {
            parts.forEach(c -> c.names(out));
        }
    }
}
