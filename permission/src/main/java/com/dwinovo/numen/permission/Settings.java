package com.dwinovo.numen.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 主人给一只同伴定的全部:八种动作各一个档位,和每种动作一张"不用问"的名单(一组名字)。不可变,任何线程只读。
 * 名单里记什么名字由动作定(挖方块记方块 id,打实体记那一只或那一种……),这里只当不透明的字符串存、比。
 */
public final class Settings {

    /** 出厂:每种动作取它的出厂档,名单都空。 */
    public static final Settings FACTORY = new Settings(factoryTiers(), Map.of());

    private final Map<Verb, Tier> tiers;
    private final Map<Verb, Set<String>> names;

    private Settings(Map<Verb, Tier> tiers, Map<Verb, Set<String>> names) {
        this.tiers = tiers;
        this.names = names;
    }

    private static Map<Verb, Tier> factoryTiers() {
        Map<Verb, Tier> out = new EnumMap<>(Verb.class);
        for (Verb v : Verb.values()) {
            out.put(v, v.factory());
        }
        return out;
    }

    /** 这种动作的档位。 */
    public Tier tier(Verb verb) {
        return tiers.get(verb);
    }

    /** 这种动作的名单,按记下的先后;没有是空表。 */
    public List<String> names(Verb verb) {
        Set<String> set = names.get(verb);
        return set == null ? List.of() : List.copyOf(set);
    }

    /** 这个名字在这种动作的名单里。 */
    public boolean listed(Verb verb, String name) {
        Set<String> set = names.get(verb);
        return set != null && set.contains(name);
    }

    public Settings withTier(Verb verb, Tier tier) {
        Objects.requireNonNull(tier, "tier");
        if (tiers.get(verb) == tier) {
            return this;
        }
        Map<Verb, Tier> next = new EnumMap<>(tiers);
        next.put(verb, tier);
        return new Settings(next, names);
    }

    /** 名字记进名单末尾;已经在名单里就是原样。 */
    public Settings withName(Verb verb, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a name on the list cannot be blank");
        }
        if (listed(verb, name)) {
            return this;
        }
        Map<Verb, Set<String>> next = copyNames();
        next.computeIfAbsent(verb, v -> new LinkedHashSet<>()).add(name);
        return new Settings(tiers, freeze(next));
    }

    /** 名字从名单里删掉;本来不在就是原样。 */
    public Settings withoutName(Verb verb, String name) {
        if (!listed(verb, name)) {
            return this;
        }
        Map<Verb, Set<String>> next = copyNames();
        next.get(verb).remove(name);
        return new Settings(tiers, freeze(next));
    }

    /** 套一个预设,见 {@link Preset}。 */
    public Settings with(Preset preset) {
        return preset.applyTo(this);
    }

    /** 每种动作都定成同一个档,名单不动。 */
    Settings withAllTiers(Tier tier) {
        Map<Verb, Tier> next = new EnumMap<>(Verb.class);
        for (Verb v : Verb.values()) {
            next.put(v, tier);
        }
        return new Settings(next, names);
    }

    private Map<Verb, Set<String>> copyNames() {
        Map<Verb, Set<String>> out = new EnumMap<>(Verb.class);
        names.forEach((verb, set) -> out.put(verb, new LinkedHashSet<>(set)));
        return out;
    }

    private static Map<Verb, Set<String>> freeze(Map<Verb, Set<String>> mutable) {
        Map<Verb, Set<String>> out = new EnumMap<>(Verb.class);
        mutable.forEach((verb, set) -> {
            if (!set.isEmpty()) {
                out.put(verb, Collections.unmodifiableSet(set));
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** 所有名单里的项,按动作的次序:面板与命令列出名单用。 */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Verb v : Verb.values()) {
            names(v).forEach(name -> out.add(new Entry(v, name)));
        }
        return out;
    }

    /** 名单里的一项:哪种动作、什么名字。 */
    public record Entry(Verb verb, String name) {}

    @Override
    public boolean equals(Object o) {
        return o instanceof Settings s && tiers.equals(s.tiers) && names.equals(s.names);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tiers, names);
    }

    @Override
    public String toString() {
        return "Settings" + tiers + names;
    }
}
