package com.dwinovo.numen.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 一组场景:原版一组,每个联动各一组。组名谁先用归谁;场景在组里按名字唯一。经 {@link Bench#suite} 登记。
 */
public final class Suite {

    /** 组名与场景名的写法:小写字母开头,只含 [a-z0-9_]。 */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private final String name;
    private final String summary;
    private final Map<String, Supplier<? extends Scenario>> scenarios = new LinkedHashMap<>();

    Suite(String name, String summary) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("评测组名不合规(小写字母开头,只含 [a-z0-9_]): '" + name + "'");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("评测组 " + name + " 没写一句话说明");
        }
        this.name = name;
        this.summary = summary;
    }

    /**
     * 加一个场景。收的是构造器:每次运行造一个新实例,场景可以把这一次生成的东西放在自己的字段里。
     */
    public Suite add(Supplier<? extends Scenario> scenario) {
        String id = scenario.get().id();
        if (!NAME.matcher(id).matches()) {
            throw new IllegalArgumentException("场景名不合规(小写字母开头,只含 [a-z0-9_]): '" + id + "'");
        }
        if (scenarios.putIfAbsent(id, scenario) != null) {
            throw new IllegalArgumentException("评测组 " + name + " 里已经有场景 " + id);
        }
        return this;
    }

    public String name() {
        return name;
    }

    public String summary() {
        return summary;
    }

    /** 按登记顺序。 */
    Map<String, Supplier<? extends Scenario>> scenarios() {
        return Collections.unmodifiableMap(scenarios);
    }

    /** {@code wanted} 里点到的场景:写 {@code all}、组名,或场景名({@code 组名/场景名} 也认)。 */
    List<String> pick(List<String> wanted) {
        List<String> out = new ArrayList<>();
        for (String id : scenarios.keySet()) {
            if (wanted.contains("all") || wanted.contains(name) || wanted.contains(id)
                    || wanted.contains(name + "/" + id)) {
                out.add(id);
            }
        }
        return out;
    }
}
