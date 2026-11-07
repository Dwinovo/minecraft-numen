package com.dwinovo.numen.permission;

import java.util.HashSet;
import java.util.Set;

/** 测试用的对象:说什么属于什么,全由用例写明。 */
final class FakeAct implements Act {

    /** 测试用的名字表:几个没有参数的,一个带参数的,一个在 attack/break 下算撤不回的。 */
    static final Vocabulary VOCAB = Vocabulary.builder()
            .name("mine", "belongs to the owner")
            .name("theirs", "belongs to somebody else")
            .name("hollow", "has a block entity")
            .irreversibleName("stuffed", "has things inside", Set.of(Verb.BREAK, Verb.ATTACK))
            .name("named", "has a name")
            .namedWithArgument("near", "is next to", "mine", arg -> arg.equals("mine") || arg.equals("theirs"))
            .namedWithOptionalArgument("inside", "is inside something", "#minecraft:village", arg -> arg.startsWith("#"))
            .build();

    private final Verb verb;
    private final String actor;
    private final Set<String> names = new HashSet<>();
    private String kind;
    private final Set<String> tags = new HashSet<>();
    private String entity;
    private final Set<String> commandNames = new HashSet<>();

    FakeAct(Verb verb, String actor) {
        this.verb = verb;
        this.actor = actor;
    }

    static FakeAct of(Verb verb) {
        return new FakeAct(verb, null);
    }

    /** 这个对象属于这些组/具有这些属性;带参数的写成 {@code near(mine)}。 */
    FakeAct with(String... facts) {
        names.addAll(Set.of(facts));
        return this;
    }

    FakeAct kind(String id) {
        this.kind = id;
        return this;
    }

    FakeAct tag(String tag) {
        tags.add(tag);
        return this;
    }

    FakeAct entity(String uuid) {
        this.entity = uuid;
        return this;
    }

    FakeAct command(String... names) {
        commandNames.addAll(Set.of(names));
        return this;
    }

    @Override
    public Verb verb() {
        return verb;
    }

    @Override
    public String actor() {
        return actor;
    }

    @Override
    public boolean is(String name, String arg) {
        return names.contains(arg == null ? name : name + "(" + arg + ")");
    }

    @Override
    public boolean ofKind(String id) {
        return id.equals(kind);
    }

    @Override
    public boolean tagged(String tag) {
        return tags.contains(tag);
    }

    @Override
    public boolean isEntity(String uuid) {
        return uuid.equals(entity);
    }

    @Override
    public boolean runs(String command) {
        return commandNames.contains(command);
    }

    @Override
    public String subject() {
        return !commandNames.isEmpty() ? commandNames.iterator().next() : kind;
    }

    @Override
    public String entityKey() {
        return entity;
    }
}
