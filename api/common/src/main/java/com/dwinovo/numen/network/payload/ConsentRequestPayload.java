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
 * 登记处({@code ConsentDesk})发起时推一次,答复、超时、顶替、任务结束时推一条撤回——卡片与
 * 世界里的轮廓只照这份画,客户端不推断。
 *
 * <p>清单正文在服务端按 {@link ConsentItem#listing} 组好再发,卡片与回执同一份措辞;
 * 轮廓只带格子与实体 id,各有上限——一张图纸可能要问几千格,卡片说得清,轮廓画不完。
 *
 * @param companion         哪只同伴
 * @param id                请求号;{@code 0} = 没有挂着的请求
 * @param reason            发起者写的原因
 * @param lines             清单正文,一堆一行(撤不回的那几行带着标记)
 * @param blocks            要描轮廓的格子({@code BlockPos#asLong})
 * @param entities          要描轮廓的实体 id
 * @param expiresAtGameTime 到这一刻按拒绝(卡片倒计时)
 */
public record ConsentRequestPayload(UUID companion, long id, String reason, List<ConsentItem.Line> lines,
                                    List<Long> blocks, List<Integer> entities, long expiresAtGameTime)
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
        return new ConsentRequestPayload(request.companion(), request.id(), request.reason(),
                ConsentItem.listing(request.items()), blocks, entities, request.expiresAtGameTime());
    }

    /** 这只同伴没有挂着的请求了。 */
    public static ConsentRequestPayload none(UUID companion) {
        return new ConsentRequestPayload(companion, 0L, "", List.of(), List.of(), List.of(), 0L);
    }

    public boolean withdrawn() {
        return id == 0L;
    }

    private static void write(RegistryFriendlyByteBuf buf, ConsentRequestPayload p) {
        buf.writeUUID(p.companion);
        buf.writeVarLong(p.id);
        buf.writeUtf(p.reason);
        buf.writeVarInt(p.lines.size());
        for (ConsentItem.Line line : p.lines) {
            buf.writeUtf(line.text());
            buf.writeBoolean(line.irreversible());
        }
        buf.writeVarInt(p.blocks.size());
        for (long b : p.blocks) {
            buf.writeLong(b);
        }
        buf.writeVarInt(p.entities.size());
        for (int e : p.entities) {
            buf.writeVarInt(e);
        }
        buf.writeVarLong(p.expiresAtGameTime);
    }

    private static ConsentRequestPayload read(RegistryFriendlyByteBuf buf) {
        UUID companion = buf.readUUID();
        long id = buf.readVarLong();
        String reason = buf.readUtf();
        int n = buf.readVarInt();
        List<ConsentItem.Line> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(new ConsentItem.Line(buf.readUtf(), buf.readBoolean()));
        }
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
        return new ConsentRequestPayload(companion, id, reason, lines, blocks, entities, buf.readVarLong());
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
