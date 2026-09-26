package com.dwinovo.numen.cli;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * 命令里点名的一只实体:{@code scan entities} 列出的运行期编号,或它的 UUID。
 *
 * <p>编号短,是她手里的写法;它只在这一次开服里有效,重启之后同一个号会发给别的东西。UUID 跨重启不变,是落盘重放的写法:
 * 派下常驻或长活的动作受理时,把点名的实体换成 {@link #of 它的 UUID} 写进重放的那一行
 * ({@link ServerSource#replayedWith}),重启后重放认的还是同一只,认不到就是它不在了。
 *
 * @param id   运行期编号;按 UUID 点名时为 null
 * @param uuid UUID;按编号点名时为 null
 */
public record EntityRef(Integer id, UUID uuid) {

    public EntityRef {
        if ((id == null) == (uuid == null)) {
            throw new IllegalArgumentException("an entity is named by its id or by its UUID, exactly one of them");
        }
    }

    /** 按运行期编号点名。 */
    public static EntityRef id(int id) {
        return new EntityRef(id, null);
    }

    /** 这只实体,按 UUID 点名:跨重启认的是同一只。 */
    public static EntityRef of(Entity entity) {
        return new EntityRef(null, entity.getUUID());
    }

    /** 它在这一层世界里的那只;不在、已被移除都是 null。 */
    public Entity in(ServerLevel level) {
        Entity found = id != null ? level.getEntity(id) : level.getEntity(uuid);
        return found == null || found.isRemoved() ? null : found;
    }

    /** 命令行上的写法:编号或 UUID 原样。 */
    public String written() {
        return id != null ? id.toString() : uuid.toString();
    }

    @Override
    public String toString() {
        return written();
    }
}
