package com.dwinovo.numen.api.permission;

import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * "这只实体是谁的"的一个回答者:只说它认得的那一类实体。是某个人的就交回那个人(玩家或同伴)的 UUID;不认得这只实体、它没有主人,
 * 交回 null。原版驯服记录的回答由 Numen 登记;你的模组有自己的记主人的办法(契约、绑定、领养),登记在这里。
 *
 * <pre>{@code
 * numen.answer(Questions.ENTITY_OWNER, "my_golems", entity ->
 *         entity instanceof MyGolem golem ? golem.masterId() : null);
 * }</pre>
 *
 * <p>规矩:只说事实,不裁决——这个人对要动手的同伴算自己、主人还是别人由权限层换算({@link Owners}),许不许由规则定。别的回答者
 * 说了什么你看不到,回答之间不讲先后;几个回答者说的不是同一个人时取最严的。抛了异常只丢你这一条、记进日志。不改世界,不做慢事:
 * 她每轮看四周时对身边每只实体都问一遍。
 *
 * <p>线程:在服务端主线程上被叫,实体是活的。
 */
@FunctionalInterface
public interface EntityOwner {

    /**
     * @return 这只实体的主人;不认得、没有主人为 null
     */
    UUID owner(Entity entity);
}
