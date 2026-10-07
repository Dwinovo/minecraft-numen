package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.ConsentRequest;
import com.dwinovo.numen.network.Wire;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client:同伴在等主人点头的那一条征询,或者"没有了"({@link #none})。
 * 登记处({@code ConsentDesk})发起时推一次,答复、超时、任务结束时推一条撤回(顶替直接推新的那条)——
 * 答复框与世界里的轮廓只照这份画,客户端不推断。
 *
 * <p>清单在服务端按 {@link ConsentItem#listing} 归好堆再发,每堆拆成给主人看的几样(动词、图标或名字、数量、理由;
 * 名字与理由可翻译,主人的客户端按自己的语言显示),和回执里交代给模型的是同一堆;"允许并记住"要写的规则行
 * ({@link ConsentItem#rememberedRows})同理;
 * 轮廓只带格子与实体 id,各有上限——一张图纸可能要问几千格,清单说得清,轮廓画不完。
 *
 * @param companion         哪只同伴
 * @param id                请求号;{@code 0} = 没有挂着的请求
 * @param lines             清单,一堆一行(撤不回的那几行带着标记)
 * @param remember          选"允许并记住"会写进主人 allow 表的规则行
 * @param blocks            要描轮廓的格子({@code BlockPos#asLong})
 * @param entities          要描轮廓的实体 id
 * @param expiresAtGameTime 到这一刻按拒绝(倒计时)
 * @param withdrawnBecause  撤回的原因(超时、任务收场、主人按了停止……),主人的客户端按自己的语言显示;主人自己答复的
 *                          撤回和挂着的请求为 null
 */
public record ConsentRequestPayload(UUID companion, long id, List<ConsentRequestPayload.Line> lines, List<String> remember,
                                    List<Long> blocks, List<Integer> entities, long expiresAtGameTime,
                                    ConsentDesk.Withdrawal withdrawnBecause)
        implements CustomPacketPayload {

    /**
     * 给主人看的一堆:答复框画成"挖 [原木图标]×6 · 玩家放的"。
     *
     * @param icon         物品图标;没有(实体、没有物品形态的方块)为 null,这时画名字
     * @param irreversible 撤不回:答复框把这一堆画成警示色
     */
    public record Line(Action.Kind kind, Item icon, Component name, int count, Component cause, boolean irreversible) {}

    private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM = ByteBufCodecs.registry(Registries.ITEM);

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
        List<Line> lines = new ArrayList<>();
        for (ConsentItem.Group group : ConsentItem.listing(request.items())) {
            ConsentItem head = group.head();
            lines.add(new Line(head.kind(), head.icon(), head.name(), group.count(), head.shownCause(),
                    group.irreversible()));
        }
        return new ConsentRequestPayload(request.companion(), request.id(), lines,
                ConsentItem.rememberedRows(request.items()), blocks, entities, request.expiresAtGameTime(), null);
    }

    /** 这只同伴没有挂着的请求了;{@code why} 为什么撤,主人自己答复的为 null。 */
    public static ConsentRequestPayload none(UUID companion, ConsentDesk.Withdrawal why) {
        return new ConsentRequestPayload(companion, 0L, List.of(), List.of(), List.of(), List.of(), 0L, why);
    }

    public boolean withdrawn() {
        return id == 0L;
    }

    private static void write(RegistryFriendlyByteBuf buf, ConsentRequestPayload p) {
        buf.writeUUID(p.companion);
        buf.writeVarLong(p.id);
        buf.writeVarInt(p.lines.size());
        for (Line line : p.lines) {
            buf.writeEnum(line.kind());
            buf.writeBoolean(line.icon() != null);
            if (line.icon() != null) {
                ITEM.encode(buf, line.icon());
            }
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, line.name());
            buf.writeVarInt(line.count());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, line.cause());
            buf.writeBoolean(line.irreversible());
        }
        buf.writeCollection(p.remember, (b, row) -> Wire.TO_CLIENT.text().encode(b, row));
        buf.writeVarInt(p.blocks.size());
        for (long b : p.blocks) {
            buf.writeLong(b);
        }
        buf.writeVarInt(p.entities.size());
        for (int e : p.entities) {
            buf.writeVarInt(e);
        }
        buf.writeVarLong(p.expiresAtGameTime);
        buf.writeBoolean(p.withdrawnBecause != null);
        if (p.withdrawnBecause != null) {
            buf.writeEnum(p.withdrawnBecause);
        }
    }

    private static ConsentRequestPayload read(RegistryFriendlyByteBuf buf) {
        UUID companion = buf.readUUID();
        long id = buf.readVarLong();
        int n = buf.readVarInt();
        List<Line> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Action.Kind kind = buf.readEnum(Action.Kind.class);
            Item icon = buf.readBoolean() ? ITEM.decode(buf) : null;
            Component name = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
            int count = buf.readVarInt();
            lines.add(new Line(kind, icon, name, count, ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf),
                    buf.readBoolean()));
        }
        List<String> remember = buf.readList(b -> Wire.TO_CLIENT.text().decode(b));
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
                buf.readBoolean() ? buf.readEnum(ConsentDesk.Withdrawal.class) : null);
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
