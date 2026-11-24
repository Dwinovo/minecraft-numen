package com.dwinovo.numen.api.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;

/**
 * 这东西是谁的:对象组 {@code self}、{@code owner}、{@code other}、{@code nature} 说的就是这四类,每个对象恰好属于一类。
 *
 * <p>权限层自己不认主人,也不记账:是谁的去问({@link Owners}),谁知道谁回答。这里只做换算——回答者说的是"某个人",把那个人换成
 * 对要动手的同伴来说是她自己({@link #SELF})、她的主人这一边({@link #OWNER}:主人本人与主人名下别的同伴)、还是别人
 * ({@link #OTHER})。没人回答就是没主人的({@link #NATURE})。几个回答者说的不是同一个人时取最严的:别人 > 主人 > 自己,
 * 不看谁先登记。
 *
 * <ul>
 *   <li>方块:问取裁决快照时抄下的那份归属({@link Facts#owners}),任何线程可读;</li>
 *   <li>实体:问一圈回答者;身体本身就是一个人的(玩家、同伴)而没人说它另有主人,按那个人算。</li>
 * </ul>
 */
enum Whose {
    SELF, OWNER, OTHER, NATURE;

    /** 这一格方块是谁的。 */
    static Whose ofBlock(BlockPos pos, Facts facts) {
        return strictest(facts.owners().at(pos), facts.actor());
    }

    /** 一只实体是谁的。 */
    static Whose ofEntity(Entity entity, Facts facts) {
        List<UUID> owners = Owners.of(entity);
        if (owners.isEmpty()) {
            return entity instanceof ServerPlayer player ? ofPerson(player.getUUID(), facts.actor()) : NATURE;
        }
        return strictest(owners, facts.actor());
    }

    /** 回答者们说的那几个人里最严的一类;一个都没有是 {@link #NATURE}。 */
    private static Whose strictest(List<UUID> owners, Actor actor) {
        Whose out = NATURE;
        for (UUID who : owners) {
            Whose said = ofPerson(who, actor);
            if (said == OTHER) {
                return OTHER;
            }
            if (said == OWNER || out == NATURE) {
                out = said;
            }
        }
        return out;
    }

    /** 一个人(玩家或同伴)对要动手的同伴来说算哪一类。 */
    private static Whose ofPerson(UUID who, Actor actor) {
        if (who.equals(actor.id())) {
            return SELF;
        }
        if (actor.owner() == null) {
            return OTHER;
        }
        return who.equals(actor.owner()) || actor.siblings().contains(who) ? OWNER : OTHER;
    }
}
