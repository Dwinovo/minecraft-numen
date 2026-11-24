package com.dwinovo.numen.api.permission;

import java.util.Set;
import java.util.UUID;

/**
 * 要动手的是谁:同伴自己、她的名字(规则的 {@code @同伴名} 对着它)、她的主人与主人名下别的同伴(对象组 {@code owner} 以谁为准)。
 * 测试里的无主之身是 {@link #NOBODY}。
 *
 * <p>是一份值:取裁决快照时在主线程上认好人({@link Permission#gateFor}),之后哪条线程拿它把"某个人"换算成自己、主人还是别人
 * ({@link Whose}),读的都是这一份,不碰活世界。
 *
 * @param id       同伴的 uuid
 * @param name     同伴的名字
 * @param owner    主人的 uuid;还没有主人为 null
 * @param siblings 主人名下此刻在游戏里的别的同伴:她们的东西算主人这一边
 */
public record Actor(UUID id, String name, UUID owner, Set<UUID> siblings) {

    public static final Actor NOBODY = new Actor(null, null, null);

    public Actor {
        siblings = Set.copyOf(siblings);
    }

    /** 主人名下没有别的同伴在场。 */
    public Actor(UUID id, String name, UUID owner) {
        this(id, name, owner, Set.of());
    }

    /** 只知道是谁、不知道名字与主人。 */
    public static Actor of(UUID id) {
        return new Actor(id, null, null);
    }
}
