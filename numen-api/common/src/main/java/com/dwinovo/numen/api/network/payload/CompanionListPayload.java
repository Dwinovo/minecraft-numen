package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.Wire;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 主人名下<b>存在的</b>同伴,以及这份名册属于哪个世界。
 *
 * <p>"存在"取自持久的 {@link com.dwinovo.numen.api.entity.CompanionRegistry},不是玩家列表:
 * 休眠的同伴也在册,因为她们确实还存在。<b>客户端据此对账并删除
 * 已遣散同伴的本地数据</b>,所以这份名册的语义必须是"存在",而不是"此刻站在世界里"
 * ——用后者做过一版,结果是同伴一休眠、或者换个存档,数据就被误删。
 *
 * <p>{@code worldId} 让"不在名册上"这句话有了作用域:客户端的
 * {@code config/numen/companions/} 是跨存档共用的,不知道名册属于哪个世界就没法安全对账。
 *
 * <p>推送时机:主人登录、召唤、遣散、被请出服务器——任何"存在状态"的变化。
 */
public record CompanionListPayload(String worldId, List<Entry> companions) implements CustomPacketPayload {

    /** Cap defends against absurd input; nobody owns hundreds of companions. */
    public static final int MAX = 64;

    /** 一行。{@code creative} 是此刻的游戏模式(不在场按生存),
     *  编辑卡的模式格用它显示当前值。{@code removedFor} 非空 = 她被外面的人或插件请出了服务器(值是当时的理由),
     *  不在世界里,等主人再召唤;空串是在世界里(或休眠)。 */
    public record Entry(UUID uuid, String name, boolean creative, String removedFor) {
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(uuid);
            Wire.writeText(buf, name);
            buf.writeBoolean(creative);
            Wire.writeText(buf, removedFor);
        }

        static Entry read(FriendlyByteBuf buf) {
            return new Entry(buf.readUUID(), Wire.readText(buf), buf.readBoolean(), Wire.readText(buf));
        }
    }

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "companion_list");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        Wire.writeText(buf, worldId);
        buf.writeCollection(companions, (b, entry) -> entry.write(b));
    }

    public static CompanionListPayload read(FriendlyByteBuf buf) {
        return new CompanionListPayload(Wire.readText(buf), Wire.readList(buf, MAX, Entry::read));
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(CompanionListPayload p) {
        com.dwinovo.numen.api.network.ClientPayloadSink.companionList.accept(p);
    }
}
