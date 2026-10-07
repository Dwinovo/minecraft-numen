package com.dwinovo.numen.sdk;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * 一只实体,脚本里的类 {@code Entity}。要一只实体的参数收下它就是它({@link EntityRef} 认它的 {@code id}),要一格的参数收下它就是它
 * 脚下那一格。
 */
@Doc("An entity near you.")
public record EntityInfo(
        @Doc("Its runtime id, which numen.fight.attack, numen.use.entity and numen.move.follow take (or pass the "
                + "whole Entity). It does not survive a restart.") int id,
        @Doc("Its type id, minecraft:zombie.") String type,
        @Doc("Where it is (decimals).") Vec3 pos,
        @Doc("Its name, when it has one.") Optional<String> name,
        @Doc("hostile, passive, player or item.") Optional<String> category,
        @Doc("Blocks from you.") Optional<Double> distance,
        @Doc("Health, for a living one.") Optional<Double> hp,
        Optional<Double> maxHp,
        @Doc("Whose it is, for a tamed one: you, your owner, or another player's name.") Optional<String> owner)
        implements Seen {

    /** 一只实体的编号、种类、位置,有名字的带上名字;别的字段由用的一方补({@link #seen})。 */
    public static EntityInfo of(Entity entity) {
        boolean named = entity.hasCustomName() || entity instanceof Player;
        return new EntityInfo(entity.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                entity.position(), named ? Optional.of(entity.getName().getString()) : Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** 同一只,补上看到它时的种类、距离、血量与主人。 */
    public EntityInfo seen(Optional<String> category, Optional<Double> distance, Optional<Double> hp,
                           Optional<Double> maxHp, Optional<String> owner) {
        return new EntityInfo(id, type, pos, name, category, distance, hp, maxHp, owner);
    }
}
