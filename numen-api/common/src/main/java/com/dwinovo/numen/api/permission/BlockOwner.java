package com.dwinovo.numen.api.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * "这一格方块是谁的"的一个回答者:知道哪些格子有主人的一方(领地、圈地、保护区的模组)登记在这里。原版里方块没有主人,所以没有
 * 原版的回答者;一个回答者都没有时,每一格都是没主人的。
 *
 * <pre>{@code
 * numen.answer(Questions.BLOCK_OWNER, "my_claims", (level, center, chunkRadius) -> {
 *     Map<ChunkPos, UUID> owners = MyClaims.copyAround(level, center, chunkRadius);   // 主线程上抄一份
 *     return owners.isEmpty() ? null : pos -> owners.get(new ChunkPos(pos));           // 之后任何线程只读这一份
 * });
 * }</pre>
 *
 * <h2>为什么是先取一份、再逐格问</h2>
 * 权限层在两处问归属:真要动手前在服务端主线程上问一格;寻路给一条路定价时在搜索线程上逐格问,那里碰不得活世界与你的活数据。
 * 所以回答分两步:{@link #around} 在主线程上把她身边一片的归属抄成一份不可变的查表({@link Claims}),之后哪条线程来问都只读这一份。
 *
 * <p>规矩:只说事实,不裁决——这个人对要动手的同伴算自己、主人还是别人由权限层换算({@link Owners}),许不许由规则定。别的回答者
 * 说了什么你看不到,回答之间不讲先后;几个回答者说的不是同一个人时取最严的。抛了异常只丢你这一条、记进日志。
 */
@FunctionalInterface
public interface BlockOwner {

    /**
     * 服务端主线程:{@code center} 周围 {@code chunkRadius} 个区块里各格的主人,交回一份此后任何线程都能读的不可变查表。每取一份
     * 裁决快照叫一次(一次动手、一次寻路各一份),别在这里做慢事。
     *
     * @return 这一片的归属;这一片里你什么都不知道为 null
     */
    Claims around(ServerLevel level, BlockPos center, int chunkRadius);

    /** 一片地方的归属,取的那一刻的样子。不可变,任何线程可读;寻路每定价一格就问一次。 */
    @FunctionalInterface
    interface Claims {

        /**
         * @param pos 问的那一格;只在这一次调用里作数(调用方会复用同一个可变的位置),要留着就先 {@code immutable()}
         * @return 这一格的主人(玩家或同伴的 UUID);不知道、没有主人为 null。格子里是空气也照答:往别人的地里放方块问的就是那一格
         */
        UUID ownerAt(BlockPos pos);
    }
}
