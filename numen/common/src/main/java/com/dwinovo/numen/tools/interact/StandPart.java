package com.dwinovo.numen.tools.interact;

import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * 盔甲架的哪一截:右键它时点在哪个高度,原版 {@code ArmorStand.interactAt} 的 {@code getClickedSlot} 就选哪个部位(空手时取下那一截的装备;
 * 手里有东西时放进哪一格由东西本身定,不看点在哪)。高度按原版的分段取各段里不与别的段重叠的一点(小号盔甲架的段更高),
 * 乘上它的缩放。手与副手都点在最底下:没落在任何一段里就是手(主手空着、副手有东西时是副手)。
 */
public enum StandPart {
    HEAD(1.7, 2.0), CHEST(1.2, 1.5), LEGS(0.7, 1.0), FEET(0.3, 0.3), HAND(0.0, 0.0), OFFHAND(0.0, 0.0);

    private final double regular;
    private final double small;

    StandPart(double regular, double small) {
        this.regular = regular;
        this.small = small;
    }

    /** 点在这一截上的那一点,相对盔甲架的位置(和真客户端发的 {@code INTERACT_AT} 一样)。 */
    public Vec3 pointOn(ArmorStand stand) {
        return new Vec3(0.0, (stand.isSmall() ? small : regular) * stand.getScale() * stand.getAgeScale(), 0.0);
    }
}
