package com.dwinovo.numen.permission;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 规则里认得的名字:对象组与属性。核心只知道名字、带不带括号参数、命中时怎么自述、哪些动词下命中算"撤不回";
 * 一个名字对不对得上一个具体对象,由 {@link Act#is} 背后的提供者回答。解析一行规则时拿它核对名字,
 * 写错了回的教学式错误里列的就是这里登记的全部名字。
 *
 * <p>登记口:原版是提供者之一,插件按 {@link Builder} 的同一个口加自己的组(只加名词,不加动词,不改判定顺序)。
 */
public final class Vocabulary {

    /**
     * 一个名字。
     *
     * @param name           规则文本里写的名字
     * @param description    命中时的英文自述,给回执与日志
     * @param argument       括号参数({@code near(owner)}、{@code structure(#minecraft:village)});不带参数的名字为 null
     * @param irreversibleOn 在这些动词下命中意味着撤不回(拆装着东西的容器、打死别人的宠物)
     */
    public record Entry(String name, String description, Argument argument, Set<Verb> irreversibleOn) {
        public Entry {
            irreversibleOn = Set.copyOf(irreversibleOn);
        }

        public boolean takesArgument() {
            return argument != null;
        }
    }

    /**
     * 括号参数的写法。
     *
     * @param hint     教学式错误里的例子,如 {@code owner}
     * @param accepts  参数写得对不对;只看写法,不看世界
     * @param required 必须带;否则不带就是"这一类里不分哪种"({@code structure} 与 {@code structure(#minecraft:village)})
     */
    public record Argument(String hint, Predicate<String> accepts, boolean required) {}

    private final Map<String, Entry> entries;

    private Vocabulary(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 登记的名字;没有这个名字返回 null(规则解析据此报错)。 */
    public Entry get(String name) {
        return entries.get(name);
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    /** 教学式错误里列的名字:带参数的写成 {@code near(...)}。 */
    public String listing() {
        return entries.values().stream().map(e -> e.takesArgument() ? e.name + "(" + e.argument.hint + ")" : e.name)
                .collect(Collectors.joining(", "));
    }

    /**
     * 这件事撤不回吗:有一个不带参数的名字在这个动词下算撤不回、而且对象身上它成立(拆装着东西的容器、打死别人的宠物)。
     * 它说的是对象与动词,与哪一行规则命中无关;征询清单据此把这一项标出来,主人点头之前看得见。
     */
    public boolean irreversible(Act act) {
        for (String name : irreversibleNames(act.verb())) {
            if (act.is(name, null)) {
                return true;
            }
        }
        return false;
    }

    /** 这个动词下命中即撤不回、且不带参数的名字,按登记顺序——"允许并记住"据此取反钉上。 */
    List<String> irreversibleNames(Verb verb) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!e.takesArgument() && e.irreversibleOn.contains(verb)) {
                out.add(e.name);
            }
        }
        return out;
    }

    public static final class Builder {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        /** 登记一个不带参数的名字。 */
        public Builder name(String name, String description) {
            return add(new Entry(name, description, null, EnumSet.noneOf(Verb.class)));
        }

        /** 登记一个不带参数、在这些动词下命中算撤不回的名字。 */
        public Builder irreversibleName(String name, String description, Set<Verb> irreversibleOn) {
            return add(new Entry(name, description, null, irreversibleOn));
        }

        /** 登记一个必须带括号参数的名字({@code near(owner)})。 */
        public Builder namedWithArgument(String name, String description, String hint, Predicate<String> accepts) {
            return add(new Entry(name, description, new Argument(hint, accepts, true), EnumSet.noneOf(Verb.class)));
        }

        /** 登记一个括号参数可带可不带的名字({@code structure}、{@code structure(#minecraft:village)})。 */
        public Builder namedWithOptionalArgument(String name, String description, String hint,
                                                 Predicate<String> accepts) {
            return add(new Entry(name, description, new Argument(hint, accepts, false), EnumSet.noneOf(Verb.class)));
        }

        private Builder add(Entry entry) {
            if (entries.putIfAbsent(entry.name, entry) != null) {
                throw new IllegalArgumentException("name registered twice: " + entry.name);
            }
            return this;
        }

        public Vocabulary build() {
            return new Vocabulary(entries);
        }
    }
}
