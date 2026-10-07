package com.dwinovo.numen.network;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.network.payload.CurrentTaskPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线上的上限只有一处,发送方在编码之前就知道装不装得下:装不下的包缩成如实说明的样子,不交给网络去断开连接。
 * 真机事故是一份 43 步的设计,{@code build show} 回执 19899 字符,撞上了随手写的 16384 字符,房主掉线、服务器停下。
 */
@Tag("mc")
class WireTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static com.mojang.brigadier.exceptions.BuiltInExceptionProvider brigadierWords;

    /** 读原版那两个私有常量要初始化它们的类,那要注册表。 */
    @BeforeAll
    static void boot() {
        brigadierWords = com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 引导换掉了 Brigadier 的报错原话;换回去,同一个 JVM 里别的命令测试读的仍是原话,不随先跑后跑而变。 */
    @AfterAll
    static void restoreBrigadierWords() {
        com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }

    /** 下行一个包的数就是原版给自定义载荷定的那个;改了版本这里先红。 */
    @Test
    void theDownlinkBudgetIsVanillasCustomPayloadLimit() throws ReflectiveOperationException {
        Field field = ClientboundCustomPayloadPacket.class.getDeclaredField("MAX_PAYLOAD_SIZE");
        field.setAccessible(true);
        assertEquals(field.getInt(null), Wire.TO_CLIENT.bytes());
    }

    /** 上行的数是各加载器与 CC: Tweaked 共同的安全线 32767,1.20.1 的原版对所有上行自定义载荷就卡它。 */
    @Test
    void theUplinkBudgetIsTheSafeLineEveryLoaderSplitsAt() {
        assertEquals(32767, Wire.TO_SERVER.bytes());
    }

    /**
     * 一条消息的总上限要大过正当消息的最大值:服务端为一位主人缓存的模块正文至多 4 MB,缺了整批重送时加上程序与清单也装得下。
     */
    @Test
    void aMessageCanCarryAWholeModuleCachePlusAProgram() {
        assertTrue(Wire.MESSAGE_BYTES >= com.dwinovo.numen.program.ProgramLimits.MODULE_CACHE_BYTES * 2);
        assertTrue(Wire.TO_SERVER.carries(Wire.MESSAGE_BYTES));
        assertFalse(Wire.TO_SERVER.carries(Wire.MESSAGE_BYTES + 1));
        assertFalse(Wire.TO_SERVER.holds(Wire.TO_SERVER.bytes() + 1));
    }

    /**
     * 判据:每个方向的整包上限加上包头(包 id 与各字段的长度前缀,留 1 KB)仍在三字节长度前缀能写的一帧以内,
     * NeoForge 才不拆包、Fabric 才不断开。
     */
    @Test
    void everyDirectionsBudgetPlusItsHeaderFitsOneFrame() {
        int header = 1024;
        for (Wire wire : Wire.values()) {
            assertTrue(wire.bytes() + header <= Wire.FRAME_BYTES, wire + " " + wire.bytes());
        }
        ByteBuf length = Unpooled.buffer();
        net.minecraft.network.VarInt.write(length, Wire.FRAME_BYTES);
        assertEquals(3, length.readableBytes(), "一帧的最大长度正好写满三字节的前缀");
        ByteBuf over = Unpooled.buffer();
        net.minecraft.network.VarInt.write(over, Wire.FRAME_BYTES + 1);
        assertEquals(4, over.readableBytes());
    }

    @Test
    void aBatchOfEventsTooBigLosesItsLongestTextsFirstAndKeepsTheRest() {
        String big = "y".repeat(Wire.TO_CLIENT.bytes() / 2);
        List<EventQueue.Entry> entries = new ArrayList<>();
        entries.add(new EventQueue.Entry(EventTypes.TASK_FINISHED, "short one", 1L, true));
        entries.add(new EventQueue.Entry(EventTypes.TASK_FINISHED, big + big, 2L, true));
        entries.add(new EventQueue.Entry(EventTypes.REFLEX, "another short one", 3L, false));

        NumenEventPayload sent = fit(new NumenEventPayload(A, entries));

        assertEquals(3, sent.entries().size(), "一条不少");
        assertEquals("short one", sent.entries().get(0).text());
        assertEquals("another short one", sent.entries().get(2).text());
        EventQueue.Entry replaced = sent.entries().get(1);
        assertEquals(EventTypes.TASK_FINISHED, replaced.type(), "种类、时刻、急不急都留着");
        assertEquals(2L, replaced.ts());
        assertTrue(replaced.urgent());
        assertTrue(replaced.text().startsWith("A task_finished event came to "), replaced.text());
        assertTrue(replaced.text().endsWith(" together with the rest, so its text was not delivered."),
                replaced.text());
    }

    @Test
    void aTaskDescriptionOrADeathCauseTooBigIsReplacedBySayingSo() {
        String big = "z".repeat(Wire.TO_CLIENT.bytes() + 1);
        CurrentTaskPayload task = fit(new CurrentTaskPayload(A, "t1", "work dig", big, false, 1200L));
        assertEquals("t1", task.taskId());
        assertEquals(1200L, task.elapsedMs());
        assertTrue(task.describe().startsWith("Its description came to "), task.describe());

        NumenDeathPayload death = fit(new NumenDeathPayload(A, big));
        assertTrue(death.cause().startsWith("The cause of death came to "), death.cause());
    }

    /** 内容本来有界的包装不下,是填它的代码错了:当场抛出、指名是哪一种包,不交给 netty。 */
    @Test
    void aBoundedPayloadThatDoesNotFitFailsLoudlyInsteadOfReachingTheWire() {
        CompanionListPayload wrong = new CompanionListPayload("w".repeat(Wire.TO_CLIENT.bytes()), List.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Wire.TO_CLIENT.fit(CompanionListPayload.STREAM_CODEC, wrong,
                        () -> new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(),
                                net.minecraft.core.RegistryAccess.EMPTY)));
        assertTrue(e.getMessage().startsWith("numen_api:companion_list came to "), e.getMessage());
    }

    /** 收的一方以整条消息的上限为防线:一个字段比整条消息还长,那不是 Numen 发的;比一个包长的字段(分片的消息里)读得下。 */
    @Test
    void aReceivedTextLongerThanTheWholeMessageIsRejectedButOneLongerThanAPacketIsRead() {
        ByteBuf buf = Unpooled.buffer();
        Wire.TO_SERVER.text().encode(buf, "q".repeat(Wire.TO_SERVER.bytes() + 1));
        assertEquals(Wire.TO_SERVER.bytes() + 1, Wire.TO_SERVER.text().decode(buf).length());
        ByteBuf over = Unpooled.buffer();
        Wire.TO_SERVER.text().encode(over, "q".repeat(Wire.MESSAGE_BYTES + 1));
        assertThrows(DecoderException.class, () -> Wire.TO_SERVER.text().decode(over));
    }

    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> T fit(T payload) {
        @SuppressWarnings("unchecked")
        net.minecraft.network.codec.StreamCodec<ByteBuf, T> codec = (net.minecraft.network.codec.StreamCodec<ByteBuf, T>)
                (Object) switch (payload) {
                    case NumenEventPayload p -> NumenEventPayload.STREAM_CODEC;
                    case CurrentTaskPayload p -> CurrentTaskPayload.STREAM_CODEC;
                    case NumenDeathPayload p -> NumenDeathPayload.STREAM_CODEC;
                    default -> throw new IllegalArgumentException(payload.toString());
                };
        T sent = Wire.TO_CLIENT.fit(codec, payload, Unpooled::buffer);
        encodes(codec, sent);
        return sent;
    }

    private static <T> void encodes(net.minecraft.network.codec.StreamCodec<ByteBuf, T> codec, T payload) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, payload);
        assertTrue(Wire.TO_CLIENT.holds(buf.readableBytes()), "送出去的装得下");
        assertEquals(payload, codec.decode(buf), "收得回来");
    }
}
