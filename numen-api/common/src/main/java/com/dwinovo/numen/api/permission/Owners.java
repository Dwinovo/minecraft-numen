package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.Questions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 问一圈"这是谁的":把登记着的回答者({@link EntityOwner}、{@link BlockOwner})挨个问一遍,各人说的主人合在一起。Numen API 自己不认
 * 主人也不记账,归属只有这一个出处——权限层、她每轮看四周、{@code numen.scan} 问的都是这里。
 *
 * <p>没人回答就是没主人的。几个回答者说的不是同一个人时这里不挑,原样都交回:对要动手的同伴算自己、主人还是别人,取哪一个,
 * 是权限层的换算({@link Whose})。一个回答者抛了异常只丢它那一条、记进日志(点它的名字),别的回答照常。
 */
public final class Owners {

    private Owners() {}

    /**
     * 服务端主线程:这只实体各回答者说的主人,按登记先后;没人说是空表。
     */
    public static List<UUID> of(Entity entity) {
        List<UUID> said = List.of();
        for (var answerer : Questions.ENTITY_OWNER.answerers().entrySet()) {
            try {
                UUID owner = answerer.getValue().owner(entity);
                if (owner != null) {
                    said = added(said, owner);
                }
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] answerer '{}' of question {} failed; what it knows is left out this time",
                        answerer.getKey(), Questions.ENTITY_OWNER.id(), e);
            }
        }
        return said;
    }

    /**
     * 服务端主线程:{@code center} 周围 {@code chunkRadius} 个区块的方块归属,各回答者的查表合成一份,此后任何线程只读。
     * 一个回答者都没有、或谁都说这一片里什么都不知道,交回 {@link Blocks#NONE}。
     */
    public static Blocks around(ServerLevel level, BlockPos center, int chunkRadius) {
        List<BlockOwner.Claims> claims = new ArrayList<>();
        for (var answerer : Questions.BLOCK_OWNER.answerers().entrySet()) {
            try {
                BlockOwner.Claims said = answerer.getValue().around(level, center, chunkRadius);
                if (said != null) {
                    claims.add(new Guarded(answerer.getKey(), said));
                }
            } catch (RuntimeException e) {
                Constants.LOG.error("[numen] answerer '{}' of question {} failed; what it knows is left out this time",
                        answerer.getKey(), Questions.BLOCK_OWNER.id(), e);
            }
        }
        return claims.isEmpty() ? Blocks.NONE : new Blocks(claims);
    }

    private static List<UUID> added(List<UUID> said, UUID owner) {
        List<UUID> out = said.isEmpty() ? new ArrayList<>(2) : said;
        out.add(owner);
        return out;
    }

    /**
     * 一片地方的方块归属,取的那一刻的样子:各回答者的查表并排放着。不可变,任何线程可读——寻路工作线程给每条边定价、主线程判
     * 一下动作,问的是同一份。
     */
    public static final class Blocks {

        /** 没人知道任何一格是谁的:每一格都没主人。 */
        public static final Blocks NONE = new Blocks(List.of());

        private final List<BlockOwner.Claims> claims;

        private Blocks(List<BlockOwner.Claims> claims) {
            this.claims = List.copyOf(claims);
        }

        /** 由现成的几份查表合成一份;运行时的那一份由 {@link Owners#around} 问登记着的回答者得来。 */
        public static Blocks of(List<BlockOwner.Claims> claims) {
            return claims.isEmpty() ? NONE : new Blocks(claims);
        }

        /** 没有任何一份查表:问哪一格都是没主人的,要扫一片的地方凭它直接跳过。 */
        public boolean isEmpty() {
            return claims.isEmpty();
        }

        /** 这一格各份查表说的主人;没人说是空表。 */
        public List<UUID> at(BlockPos pos) {
            List<UUID> said = List.of();
            for (BlockOwner.Claims each : claims) {
                UUID owner = each.ownerAt(pos);
                if (owner != null) {
                    said = added(said, owner);
                }
            }
            return said;
        }
    }

    /**
     * 一个回答者交回的查表,读它时隔离它的异常:抛了就记一条日志(点回答者的名字)、这份查表此后不再问——寻路每定价一格就读一次,
     * 一份坏掉的查表不该每一格都抛一遍。
     */
    private static final class Guarded implements BlockOwner.Claims {

        private final String answerer;
        private final BlockOwner.Claims claims;
        private volatile boolean failed;

        Guarded(String answerer, BlockOwner.Claims claims) {
            this.answerer = answerer;
            this.claims = claims;
        }

        @Override
        public UUID ownerAt(BlockPos pos) {
            if (failed) {
                return null;
            }
            try {
                return claims.ownerAt(pos);
            } catch (RuntimeException e) {
                failed = true;
                Constants.LOG.error("[numen] answerer '{}' of question {} failed; what it knows is left out of this snapshot",
                        answerer, Questions.BLOCK_OWNER.id(), e);
                return null;
            }
        }
    }
}
