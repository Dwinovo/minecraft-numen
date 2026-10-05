package com.dwinovo.numen.sdk;

/**
 * 地上的一个掉落物,脚本里的类 {@code Item}:一只实体,加上它是什么、几个、多久之后谁都能捡。
 */
@Doc("A dropped item lying on the ground: an Entity with what it is.")
public record ItemInfo(@Flatten EntityInfo entity,
                       @Doc("The item id, minecraft:raw_iron.") String item,
                       @Doc("How many.") int count,
                       @Doc("Ticks before anyone can pick it up; 0 = now.") int pickupDelay) implements Seen {
}
