package com.dwinovo.numen.permission;

/**
 * 一件要裁决的事:谁({@link #actor})要对什么对象做什么({@link #verb})。规则核心只通过这个口问对象,不认识游戏里的
 * 方块、实体与物品——适配层把游戏里的一下动作翻成它,对象组怎么判、种类与标签怎么认都由适配层回答。
 *
 * <p>所有回答只陈述事实,不裁决。
 */
public interface Act {

    Verb verb();

    /** 动手的同伴的名字(规则的 {@code @同伴名} 对着它);不知道是谁为 null。 */
    String actor();

    /**
     * 对象属不属于一个命名的组、有没有一项属性({@link Vocabulary} 里登记的名字)。
     *
     * @param name 组或属性的名字
     * @param arg  括号里的参数({@code near(owner)} 的 {@code owner});没有为 null
     */
    boolean is(String name, String arg);

    /** 对象的种类是不是这个 id({@code minecraft:chest}):方块、实体种类或物品,由动词定看哪一个。 */
    boolean ofKind(String id);

    /** 对象的种类在不在这个标签里({@code #} 之后的那截,{@code minecraft:beds})。 */
    boolean tagged(String tag);

    /** 对象是不是这一只实体({@code entity:<uuid>} 的 uuid)。 */
    boolean isEntity(String uuid);

    /** 指令的根名,或与它同指一个节点的别名,是不是这个名字。 */
    boolean runs(String command);

    /**
     * "允许并记住"钉上的对象:种类 id,指令是她打的那个根名;没有对象为 null。
     */
    String subject();

    /** 对象是一只实体时它的 uuid 文本;否则为 null。"允许并记住"认实体认的是那一只。 */
    String entityKey();
}
