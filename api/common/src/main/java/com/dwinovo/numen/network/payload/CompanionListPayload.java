package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 主人名下<b>存在的</b>同伴,以及这份名册属于哪个世界。
 *
 * <p>"存在"取自持久的 {@link com.dwinovo.numen.entity.CompanionRegistry},不是玩家列表:
 * 死了、正等复活、休眠的同伴都在册,因为她们确实还存在。<b>客户端据此对账并删除
 * 已遣散同伴的本地数据</b>,所以这份名册的语义必须是"存在",而不是"此刻站在世界里"
 * ——用后者做过一版,结果是同伴一死、或者换个存档,数据就被误删。
 *
 * <p>{@code worldId} 让"不在名册上"这句话有了作用域:客户端的
 * {@code config/numen/companions/} 是跨存档共用的,不知道名册属于哪个世界就没法安全对账。
 *
 * <p>推送时机:主人登录、召唤、遣散、死亡、复活——任何"存在或存活状态"的变化。
 */
public record CompanionListPayload(String worldId, List<Entry> companions) implements CustomPacketPayload {

    /** Cap defends against absurd input; nobody owns hundreds of companions. */
    public static final int MAX = 64;

    /** 一行。{@code respawnInMs} = {@link com.dwinovo.numen.entity.CompanionRoster#ALIVE} 是活着,
     *  ≥0 是死了、还有这么久复活。{@code creative} 是此刻的游戏模式(不在场按生存),
     *  编辑卡的模式格用它显示当前值。 */
    public record Entry(UUID uuid, String name, long respawnInMs, boolean creative) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, Entry::uuid,
                        ByteBufCodecs.stringUtf8(256), Entry::name,
                        ByteBufCodecs.VAR_LONG, Entry::respawnInMs,
                        ByteBufCodecs.BOOL, Entry::creative,
                        Entry::new);
    }

    public static final Type<CompanionListPayload> TYPE = new Type<>(
            new ResourceLocation(Constants.MOD_ID, "companion_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionListPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), CompanionListPayload::worldId,
                    Entry.CODEC.apply(ByteBufCodecs.list(MAX)), CompanionListPayload::companions,
                    CompanionListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(CompanionListPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.companionList.accept(p);
    }
}
