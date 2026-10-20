package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.script.ScriptType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 点名的一只实体:{@link EntityLabel} 的身份({@code zombie@3f9a1c},玩家是 {@code player@ab12cd}),或那只实体的表(带 {@code id})。
 * 身份跨重启不变,所以占身体的活重启后再跑的那一行写的就是它,认的还是同一只,认不到就是它不在了。
 *
 * @param label 通过 {@link EntityLabel#valid} 的身份
 */
public record EntityRef(String label) {

    public EntityRef {
        if (!EntityLabel.valid(label)) {
            throw new IllegalArgumentException("not an entity label: " + label);
        }
    }

    /** 这只实体。 */
    public static EntityRef of(Entity entity) {
        return new EntityRef(EntityLabel.of(entity));
    }

    /**
     * 它在这一层世界里的那只;不在是 null。
     *
     * @throws ApiError {@link ErrorKind#BAD_ARGUMENT}:撞码,同一个短码对着好几只,列出是哪几只
     */
    public Entity in(ServerLevel level) {
        return switch (EntityLabel.find(level, label)) {
            case EntityLabel.Match.Found<Entity> found -> found.entity();
            case EntityLabel.Match.None<Entity> none -> null;
            case EntityLabel.Match.Ambiguous<Entity> clash -> throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    label + " names " + clash.candidates().size() + " entities at once (same short code): "
                            + clash.candidates().stream()
                                    .map(e -> EntityLabel.of(e) + " at " + Positions.literal(e.blockPosition()))
                                    .collect(Collectors.joining("; "))
                            + " — act on them by where they stand, or wait for one to move away",
                    null);
        };
    }

    @Override
    public String toString() {
        return label;
    }

    /** 身份、带 {@code id} 的表(一只 Entity)读成它;写回去是身份。 */
    static final Codec<EntityRef> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(ScriptType.STRING, new ScriptType.Named("Entity"));
        }

        @Override
        public EntityRef decode(Object value) {
            Object id = value instanceof Map<?, ?> table ? table.get("id") : value;
            if (id instanceof String s && EntityLabel.valid(s)) {
                return new EntityRef(s);
            }
            throw new BadValue("expected an entity: its id as numen.scan.entities lists it (\"zombie@3f9a1c\", a player is "
                    + "\"player@ab12cd\": kind@ and 6 hex digits), or the Entity itself; got " + BadValue.given(value));
        }

        @Override
        public Object encode(EntityRef value) {
            return value.label;
        }
    };
}
