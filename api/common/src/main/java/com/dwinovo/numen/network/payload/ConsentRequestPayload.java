package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.ConsentRequest;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client:同伴在等主人点头的那一条征询,或者"没有了"({@link #none})。
 * 登记处({@code ConsentDesk})发起时推一次,答复、超时、任务结束时推一条撤回(顶替直接推新的那条)——
 * 答复框、提示条与世界里的轮廓只照这份画,客户端不推断。
 *
 * <p>清单正文与"允许并记住"要写的规则行在服务端组好再发({@link ConsentItem#listing}、
 * {@link ConsentItem#rememberedRows}),主人看到的与回执里交代给模型的同一份措辞;
 * 轮廓只带格子与实体 id,各有上限——一张图纸可能要问几千格,清单说得清,轮廓画不完。
 *
 * @param companion         哪只同伴
 * @param id                请求号;{@code 0} = 没有挂着的请求
 * @param lines             清单正文,一堆一行(撤不回的那几行带着标记)
 * @param remember          选"允许并记住"会写进主人 allow 表的规则行
 * @param blocks            要描轮廓的格子({@code BlockPos#asLong})
 * @param entities          要描轮廓的实体 id
 * @param expiresAtGameTime 到这一刻按拒绝(倒计时)
 * @param withdrawnBecause  撤回的原因(超时、任务结束……);主人自己答复的撤回和挂着的请求为空串
 */
public record ConsentRequestPayload(UUID companion, long id, List<ConsentItem.Line> lines, List<String> remember,
                                    List<Long> blocks, List<Integer> entities, long expiresAtGameTime,
                                    String withdrawnBecause)
        implements CustomPacketPayload {

    /** 一次最多描多少格、多少只实体的轮廓。 */
    public static final int MAX_OUTLINED_BLOCKS = 256;
    public static final int MAX_OUTLINED_ENTITIES = 32;

    public static final Type<ConsentRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "consent_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConsentRequestPayload> STREAM_CODEC =
            StreamCodec.of(ConsentRequestPayload::write, ConsentRequestPayload::read);

    public static ConsentRequestPayload of(ConsentRequest request) {
        List<Long> blocks = new ArrayList<>();
        List<Integer> entities = new ArrayList<>();
        for (ConsentItem item : request.items()) {
            if (item.entityId() != ConsentItem.NO_ENTITY) {
                if (entities.size() < MAX_OUTLINED_ENTITIES) {
                    entities.add(item.entityId());
                }
            } else if (item.pos() != null && blocks.size() < MAX_OUTLINED_BLOCKS) {
                blocks.add(item.pos().asLong());
            }
        }
        return new ConsentRequestPayload(request.companion(), request.id(), ConsentItem.listing(request.items()),
                ConsentItem.rememberedRows(request.items()), blocks, entities, request.expiresAtGameTime(), "");
    }

    /** 这只同伴没有挂着的请求了;{@code why} 为什么撤,主人自己答复的为空串。 */
    public static ConsentRequestPayload none(UUID companion, String why) {
        return new ConsentRequestPayload(companion, 0L, List.of(), List.of(), List.of(), List.of(), 0L,
                why == null ? "" : why);
    }

    public boolean withdrawn() {
        return id == 0L;
    }

    private static void write(RegistryFriendlyByteBuf buf, ConsentRequestPayload p) {
        buf.writeUUID(p.companion);
        buf.writeVarLong(p.id);
        buf.writeVarInt(p.lines.size());
        for (ConsentItem.Line line : p.lines) {
            buf.writeUtf(line.text());
            buf.writeBoolean(line.irreversible());
        }
        buf.writeCollection(p.remember, net.minecraft.network.FriendlyByteBuf::writeUtf);
        buf.writeVarInt(p.blocks.size());
        for (long b : p.blocks) {
            buf.writeLong(b);
        }
        buf.writeVarInt(p.entities.size());
        for (int e : p.entities) {
            buf.writeVarInt(e);
        }
        buf.writeVarLong(p.expiresAtGameTime);
        buf.writeUtf(p.withdrawnBecause);
    }

    private static ConsentRequestPayload read(RegistryFriendlyByteBuf buf) {
        UUID companion = buf.readUUID();
        long id = buf.readVarLong();
        int n = buf.readVarInt();
        List<ConsentItem.Line> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(new ConsentItem.Line(buf.readUtf(), buf.readBoolean()));
        }
        List<String> remember = buf.readList(net.minecraft.network.FriendlyByteBuf::readUtf);
        n = buf.readVarInt();
        List<Long> blocks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            blocks.add(buf.readLong());
        }
        n = buf.readVarInt();
        List<Integer> entities = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entities.add(buf.readVarInt());
        }
        long expiresAtGameTime = buf.readVarLong();
        return new ConsentRequestPayload(companion, id, lines, remember, blocks, entities, expiresAtGameTime,
                buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler (client main thread). */
    public static void handle(ConsentRequestPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.consent.accept(p);
    }
}
