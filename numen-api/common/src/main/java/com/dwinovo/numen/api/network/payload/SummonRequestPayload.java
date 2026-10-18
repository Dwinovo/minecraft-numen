package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.Companions;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 主人在面板的"+"上按名字召唤一只同伴。和 {@code /numen player summon} 同一个入口({@link Companions#summonFor}),
 * 召唤是按(主人,名字)幂等的:再召唤已有的名字就是叫醒她。
 *
 * <p>召唤只要名字和模式,不设任何前提——档案、人设、声线、皮肤都是召唤之后的绑定({@code CompanionBinding})。名字限定
 * Minecraft 官方命名规则(3~16 位英文/数字/下划线)。
 */
public record SummonRequestPayload(String name, boolean creative) implements CustomPacketPayload {

    public static final int MAX_NAME = 16;

    public static final Type<SummonRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "summon_request"));

    public static final StreamCodec<ByteBuf, SummonRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_NAME), SummonRequestPayload::name,
                    ByteBufCodecs.BOOL, SummonRequestPayload::creative,
                    SummonRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server main thread. */
    public static void handle(SummonRequestPayload p, ServerPlayer owner) {
        Companions.summonFor(owner, p.name() == null ? "" : p.name().trim(), p.creative(), summoned -> {
            if (summoned.problem() != null) {
                owner.sendSystemMessage(summoned.problem());
            }
        });
    }
}
