package com.dwinovo.numen.permission;

/**
 * 对象条件里的一个最小单位。
 *
 * @param type           哪一种
 * @param name           内容:组或属性的名字、标签 id(不带 {@code #})、种类 id、实体 uuid、指令根名;{@code *} 是 {@code *}
 * @param arg            {@link Type#NAMED} 的括号参数,没有为 null
 * @param description    命中时的英文自述;{@code *} 为空串
 */
public record Atom(Type type, String name, String arg, String description) {

    public enum Type {
        /** {@code *}:什么对象都行。 */
        ANY,
        /** {@link Vocabulary} 里登记的对象组或属性,可带括号参数。 */
        NAMED,
        /** {@code #minecraft:beds}。 */
        TAG,
        /** {@code minecraft:chest}。 */
        ID,
        /** {@code entity:<uuid>}:某一只实体。 */
        ENTITY,
        /** 指令的根名。 */
        COMMAND
    }

    static Atom any() {
        return new Atom(Type.ANY, "*", null, "");
    }

    static Atom named(Vocabulary.Entry entry, String arg) {
        return new Atom(Type.NAMED, entry.name(), arg, entry.description());
    }

    static Atom tag(String id) {
        return new Atom(Type.TAG, id, null, "is #" + id);
    }

    static Atom id(String id) {
        return new Atom(Type.ID, id, null, "is " + id);
    }

    static Atom entity(String uuid) {
        return new Atom(Type.ENTITY, uuid, null, "is entity " + uuid);
    }

    static Atom command(String root) {
        return new Atom(Type.COMMAND, root, null, "runs /" + root);
    }

    /** 规则文本里这一项的写法。 */
    public String text() {
        return switch (type) {
            case ANY, ID, COMMAND -> name;
            case NAMED -> arg == null ? name : name + "(" + arg + ")";
            case TAG -> "#" + name;
            case ENTITY -> "entity:" + name;
        };
    }

    /** 命中这个动作的对象吗。 */
    boolean matches(Act act) {
        return switch (type) {
            case ANY -> true;
            case NAMED -> act.is(name, arg);
            case TAG -> act.tagged(name);
            case ID -> act.ofKind(name);
            case ENTITY -> act.isEntity(name);
            case COMMAND -> act.runs(name);
        };
    }
}
