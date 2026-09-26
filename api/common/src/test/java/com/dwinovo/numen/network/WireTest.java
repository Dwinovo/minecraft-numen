package com.dwinovo.numen.network;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.network.payload.CurrentTaskPayload;
import com.dwinovo.numen.network.payload.ExecuteToolPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.network.payload.TaskResultPayload;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    /** 两个数对着原版核:原版把它们写成私有常量,改了版本这里先红。 */
    @Test
    void theBudgetsAreVanillasOwnCustomPayloadLimitsPerDirection() throws ReflectiveOperationException {
        assertEquals(vanilla(ClientboundCustomPayloadPacket.class), Wire.TO_CLIENT.bytes());
        assertEquals(vanilla(ServerboundCustomPayloadPacket.class), Wire.TO_SERVER.bytes());
    }

    private static int vanilla(Class<?> packet) throws ReflectiveOperationException {
        Field field = packet.getDeclaredField("MAX_PAYLOAD_SIZE");
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    void aResultThatFitsGoesOutUntouched() {
        TaskResultPayload small = new TaskResultPayload(A, "call-1", TaskResult.ok("done").toJson());
        assertSame(small, fit(small));
    }

    /** 事故那一种:回执比一个下行包大。换成同一次调用的一条失败,说清多大、上限多少、怎么要少一点。 */
    @Test
    void aResultTooBigForOnePayloadBecomesAFailureForTheSameCall() {
        String huge = TaskResult.ok("x".repeat(Wire.TO_CLIENT.bytes() + 10)).toJson();
        TaskResultPayload sent = fit(new TaskResultPayload(A, "call-7", huge));

        assertEquals("call-7", sent.toolCallId(), "回给的还是那一次调用");
        JsonObject result = JsonParser.parseString(sent.resultJson()).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        String message = result.get("message").getAsString();
        assertTrue(message.startsWith("The result of this call came to "), message);
        assertTrue(message.contains("more than the 1048576 bytes one message to your client can carry, so it was "
                + "not delivered."), message);
        assertTrue(message.contains("--page"), "说怎么要少一点: " + message);
        int bytes = result.getAsJsonObject("data").get("result_bytes").getAsInt();
        assertTrue(bytes > Wire.TO_CLIENT.bytes(), "报的是整包的真实大小: " + bytes);
        assertEquals(Wire.TO_CLIENT.bytes(), result.getAsJsonObject("data").get("limit_bytes").getAsInt());
        encodes(TaskResultPayload.STREAM_CODEC, sent);
    }

    /** 多字节的字也按字节算:一万个汉字不到上限的字符数,字节数早就超了。 */
    @Test
    void theBudgetCountsEncodedBytesNotCharacters() {
        String chinese = "字".repeat(Wire.TO_CLIENT.bytes() / 3 + 100);
        assertTrue(chinese.length() < Wire.TO_CLIENT.bytes());
        TaskResultPayload sent = fit(new TaskResultPayload(A, "call-8", TaskResult.ok(chinese).toJson()));
        assertFalse(JsonParser.parseString(sent.resultJson()).getAsJsonObject().get("success").getAsBoolean());
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
        CurrentTaskPayload task = fit(new CurrentTaskPayload(A, "t1", "work mine", big, false, 1200L));
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

    /** 上行的工具调用装不下:不送,就地回给模型一条失败——服务端根本不知道这次调用,不会有结果回来。 */
    @Test
    void aToolCallTooBigForTheServerIsAnsweredOnTheClientAndNotSent() {
        AtomicReference<String> completed = new AtomicReference<>();
        String args = "{\"command\":\"build layer 0 0 0 " + "#".repeat(Wire.TO_SERVER.bytes()) + "\"}";
        ServerToolTransport.ship(new ToolCall("call-9", "command", args, () -> A, completed::set));

        JsonObject result = JsonParser.parseString(completed.get()).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        String message = result.get("message").getAsString();
        assertTrue(message.startsWith("This call came to "), message);
        assertTrue(message.contains("more than the 32767 bytes one message to the server can carry, so it was not "
                + "sent."), message);
        assertTrue(message.contains("several shorter calls"), message);
    }

    @Test
    void aToolCallThatFitsTheServerRoundTrips() {
        String args = "{\"command\":\"build layer 0 0 0 " + "#".repeat(20_000) + "\"}";
        ExecuteToolPayload call = new ExecuteToolPayload(A, "call-10", "command", args);
        ByteBuf buf = Unpooled.buffer();
        ExecuteToolPayload.STREAM_CODEC.encode(buf, call);
        assertTrue(Wire.TO_SERVER.holds(buf.readableBytes()));
        assertEquals(call, ExecuteToolPayload.STREAM_CODEC.decode(buf), "从前 16384 字符就拦下,现在按整包的字节算");
    }

    /** 收的一方以整包上限为防线:一个字段比整包还长,那不是 Numen 发的。 */
    @Test
    void aReceivedTextLongerThanTheWholeBudgetIsRejected() {
        ByteBuf buf = Unpooled.buffer();
        Wire.TO_SERVER.text().encode(buf, "q".repeat(Wire.TO_SERVER.bytes() + 1));
        assertThrows(DecoderException.class, () -> Wire.TO_SERVER.text().decode(buf));
    }

    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> T fit(T payload) {
        @SuppressWarnings("unchecked")
        net.minecraft.network.codec.StreamCodec<ByteBuf, T> codec = (net.minecraft.network.codec.StreamCodec<ByteBuf, T>)
                (Object) switch (payload) {
                    case TaskResultPayload p -> TaskResultPayload.STREAM_CODEC;
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
