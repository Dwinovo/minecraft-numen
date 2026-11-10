package com.dwinovo.numen.api.network.payload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.SummonSetup;
import com.dwinovo.numen.api.network.Wire;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 主人在召唤卡上按名字召唤一只同伴。和 {@code /numen player summon} 同一个入口({@link Companions#summonFor}),
 * 召唤是按(主人,名字)幂等的:再召唤已有的名字就是叫醒她。
 *
 * <p>名字是唯一必填的,限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线)。召唤卡上改过的配置({@link SummonSetup}:三项库绑定、
 * 模式、皮肤)随这一包一起来,服务端在她到达的那一刻落下;没改的不在包里。皮肤带着主人本机文件夹里的原图,所以这一包可以分片送。
 */
public record SummonRequestPayload(String name, SummonSetup setup) implements CustomPacketPayload, Wire.Fragmentable {

    public static final int MAX_NAME = 16;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "summon_request");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, MAX_NAME);
        setup.write(buf);
    }

    public static SummonRequestPayload read(FriendlyByteBuf buf) {
        return new SummonRequestPayload(buf.readUtf(MAX_NAME), SummonSetup.read(buf));
    }

    /** Server main thread. */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        Companions.summonFor(owner, p.name() == null ? "" : p.name().trim(), p.setup(), summoned -> {
            if (summoned.problem() != null) {
                owner.sendSystemMessage(summoned.problem());
            }
        });
    }
}
