package com.dwinovo.numen.api.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * 这东西是谁的:对象组 {@code self}、{@code owner}、{@code other}、{@code structure}、{@code nature}、{@code unknown} 说的就是
 * 这六类。每个对象恰好属于一类,判出来就停,不看后面。
 *
 * <h2>方块的次序</h2>
 * <ol>
 *   <li>放置记录最准,所以最先看({@link PlacedBlocks}):她放的 {@link #SELF};主人或主人名下别的同伴放的 {@link #OWNER};
 *       别人或别人名下同伴放的 {@link #OTHER};不知道是谁放的(记下放的人之前的存档)直接 {@link #UNKNOWN}——有人放过,
 *       不能当成地形。主人在村庄里放的方块是主人的,不是村庄的;</li>
 *   <li>世界生成时记下的结构件({@link Structures})里的 {@link #STRUCTURE}。结构先于自然:村庄件范围里的泥土、石头算村庄的。
 *       宁可在村子里多问一句,也不把沙漠村的砂岩房当成沙漠;</li>
 *   <li>种类在自然方块表里({@link NaturalBlocks})的 {@link #NATURE};</li>
 *   <li>以上都不是:{@link #UNKNOWN}——装模组前盖的木屋多半落在这里。不知道是谁的就问。专用服务器上它按 {@link #OTHER} 算
 *       ({@link PermissionConfig#unknownAsOther}):多人服上说不清的多半是别人盖的。</li>
 * </ol>
 *
 * <h2>实体</h2>
 * 同一套名字的前三类与 {@link #NATURE}(野生的、没主人的):驯服记录说得清每一只有没有主人、主人是谁;玩家本人、别的玩家与别人的同伴
 * 也不是野生的。没有 {@link #STRUCTURE} 与 {@link #UNKNOWN}。
 */
enum Whose {
    SELF, OWNER, OTHER, STRUCTURE, NATURE, UNKNOWN;

    /** 一个放置记号的主人对要动手的同伴来说算哪一类。 */
    static Whose ofPlacer(PlacedBlocks.Placer placer, Actor actor) {
        if (placer.equals(PlacedBlocks.Placer.UNKNOWN)) {
            return UNKNOWN;
        }
        if (placer.id().equals(actor.id())) {
            return SELF;
        }
        if (actor.owner() != null && (placer.id().equals(actor.owner()) || actor.owner().equals(placer.owner()))) {
            return OWNER;
        }
        return OTHER;
    }

    /** 一个方块动作落点上的方块是谁的;不在格子上的动作不问这个。 */
    static Whose ofBlock(Action action, Facts facts) {
        BlockPos pos = action.pos();
        PlacedBlocks.Placer placer = facts.placed() == null ? null
                : facts.placed().placerAt(pos, facts.view().getBlockState(pos));
        Whose whose;
        if (placer != null) {
            whose = ofPlacer(placer, facts.actor());
        } else if (facts.structures().pieceAt(pos) != null) {
            whose = STRUCTURE;
        } else if (NaturalBlocks.is(facts.view().getBlockState(pos), pos, facts.view())) {
            whose = NATURE;
        } else {
            whose = UNKNOWN;
        }
        return whose == UNKNOWN && facts.unknownAsOther() ? OTHER : whose;
    }

    /** 一只实体是谁的。 */
    static Whose ofEntity(Entity entity, Facts facts) {
        Actor actor = facts.actor();
        UUID owner = Groups.ownerOf(entity);
        if (owner == null) {
            return entity instanceof ServerPlayer player ? ofPerson(player.getUUID(), player, actor) : NATURE;
        }
        return ofPerson(owner, facts.live() == null ? null
                : facts.live().getServer().getPlayerList().getPlayer(owner), actor);
    }

    /** 一个人(玩家或同伴)对要动手的同伴来说算哪一类;{@code online} 是他此刻的在线身体,用来认出主人名下的同伴。 */
    private static Whose ofPerson(UUID who, ServerPlayer online, Actor actor) {
        if (who.equals(actor.id())) {
            return SELF;
        }
        if (actor.owner() == null) {
            return OTHER;
        }
        boolean ownersCompanion = online instanceof com.dwinovo.numen.api.entity.NumenPlayer companion
                && actor.owner().equals(companion.getOwnerUuid());
        return who.equals(actor.owner()) || ownersCompanion ? OWNER : OTHER;
    }
}
