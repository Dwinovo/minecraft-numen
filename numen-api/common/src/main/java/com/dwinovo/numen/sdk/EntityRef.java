package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 点名的一只实体:{@code numen.scan.entities} 列出的运行期编号,或那只实体的表(带 {@code id}),或它的 UUID。
 *
 * <p>编号短,是她手里的写法;它只在这一次开服里有效,重启之后同一个号会发给别的东西。UUID 跨重启不变:占身体的活受理时把点名的实体
 * 换成 {@link #of 它的 UUID} 写进重启后再跑的那一行({@link Job#replayedAs}),重启后认的还是同一只,认不到就是它不在了。
 *
 * @param id   运行期编号;按 UUID 点名时为 null
 * @param uuid UUID;按编号点名时为 null
 */
public record EntityRef(Integer id, UUID uuid) {

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

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

    @Override
    public String toString() {
        return id != null ? id.toString() : uuid.toString();
    }

    /** 编号、带 {@code id} 的表或 UUID 读成它;写回去是编号或 UUID。 */
    static final Codec<EntityRef> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(ScriptType.INTEGER, new ScriptType.Named("Entity"));
        }

        @Override
        public EntityRef decode(Object value) {
            Object id = value instanceof Map<?, ?> table ? table.get("id") : value;
            if (id instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                    && n.doubleValue() == (int) n.doubleValue()) {
                return EntityRef.id((int) n.doubleValue());
            }
            if (id instanceof String s && UUID_TEXT.matcher(s).matches()) {
                return new EntityRef(null, UUID.fromString(s));
            }
            if (id instanceof String s && s.matches("\\d{1,9}")) {
                throw new BadValue("an entity id is a number; got " + BadValue.given(value), Long.parseLong(s));
            }
            throw new BadValue("expected an entity: its id as numen.scan.entities lists it (184), or the Entity "
                    + "itself; got " + BadValue.given(value));
        }

        @Override
        public Object encode(EntityRef value) {
            return value.id != null ? (Object) (long) value.id : value.uuid.toString();
        }
    };
}
