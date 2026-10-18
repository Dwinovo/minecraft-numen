package com.dwinovo.numen.api.permission;

import java.util.UUID;

/**
 * 要动手的是谁:同伴自己、她的名字(规则的 {@code @同伴名} 对着它)、她的主人(对象组 {@code owner} 以谁为准)。
 * 测试里的无主之身是 {@link #NOBODY}。
 *
 * @param id    同伴的 uuid
 * @param name  同伴的名字
 * @param owner 主人的 uuid;还没有主人为 null
 */
public record Actor(UUID id, String name, UUID owner) {

    public static final Actor NOBODY = new Actor(null, null, null);

    /** 只知道是谁、不知道名字与主人。 */
    public static Actor of(UUID id) {
        return new Actor(id, null, null);
    }
}
