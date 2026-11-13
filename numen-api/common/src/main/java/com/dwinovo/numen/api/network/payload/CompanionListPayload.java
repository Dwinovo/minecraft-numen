package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.Attendance;
import com.dwinovo.numen.api.network.Wire;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ByIdMap;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 这位主人用得了的、<b>存在的</b>同伴(他创建的,加上分享给他的),以及这份名册属于哪个世界。
 *
 * <p>"存在"取自持久的 {@link com.dwinovo.numen.api.entity.CompanionRegistry},不是玩家列表:
 * 休眠的同伴也在册,因为她们确实还存在。<b>客户端据此对账并删除
 * 已遣散同伴的本地数据</b>,所以这份名册的语义必须是"存在",而不是"此刻站在世界里"
 * ——用后者做过一版,结果是同伴一休眠、或者换个存档,数据就被误删。
 *
 * <p>{@code worldId} 让"不在名册上"这句话有了作用域:客户端的
 * {@code config/numen/companions/} 是跨存档共用的,不知道名册属于哪个世界就没法安全对账。
 *
 * <p>推送时机:主人登录、召唤、下线、删除、被请出服务器、主人表变了——任何"存在状态"的变化。
 */
public record CompanionListPayload(String worldId, List<Entry> companions) implements CustomPacketPayload {

    /** Cap defends against absurd input; nobody owns hundreds of companions. */
    public static final int MAX = 64;

    /** 一行。{@code creative} 是此刻的游戏模式(不在场按生存),
     *  编辑卡的模式格用它显示当前值。{@code outside} 是她被留在外面的状态与原因(主人让她下线、服务器把她请出去,见
     *  {@link Attendance.Outside}):留在外面的不在世界里,等主人再召唤。{@code creator} 是创建她的那位玩家(收到这份名册的人
     *  不是他,她就是分享来的),{@code creatorName} 是他给人读的名字,查不到是空串。 */
    public record Entry(UUID uuid, String name, boolean creative, Attendance.Outside outside,
                        UUID creator, String creatorName) {
        private static final StreamCodec<io.netty.buffer.ByteBuf, Attendance.Why> WHY = ByteBufCodecs.idMapper(
                ByIdMap.continuous(Attendance.Why::ordinal, Attendance.Why.values(), ByIdMap.OutOfBoundsStrategy.ZERO),
                Attendance.Why::ordinal);
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.of(Entry::write, Entry::read);

        private static void write(RegistryFriendlyByteBuf buf, Entry e) {
            UUIDUtil.STREAM_CODEC.encode(buf, e.uuid);
            Wire.TO_CLIENT.text().encode(buf, e.name);
            buf.writeBoolean(e.creative);
            WHY.encode(buf, e.outside.why());
            Wire.TO_CLIENT.text().encode(buf, e.outside.reason());
            UUIDUtil.STREAM_CODEC.encode(buf, e.creator);
            Wire.TO_CLIENT.text().encode(buf, e.creatorName);
        }

        private static Entry read(RegistryFriendlyByteBuf buf) {
            UUID uuid = UUIDUtil.STREAM_CODEC.decode(buf);
            String name = Wire.TO_CLIENT.text().decode(buf);
            boolean creative = buf.readBoolean();
            Attendance.Why why = WHY.decode(buf);
            String reason = Wire.TO_CLIENT.text().decode(buf);
            return new Entry(uuid, name, creative, new Attendance.Outside(why, reason),
                    UUIDUtil.STREAM_CODEC.decode(buf), Wire.TO_CLIENT.text().decode(buf));
        }
    }

    public static final Type<CompanionListPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "companion_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionListPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Wire.TO_CLIENT.text(), CompanionListPayload::worldId,
                    Entry.CODEC.apply(ByteBufCodecs.list(MAX)), CompanionListPayload::companions,
                    CompanionListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(CompanionListPayload p) {
        com.dwinovo.numen.api.network.ClientPayloadSink.companionList.accept(p);
    }
}
