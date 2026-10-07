package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.network.payload.ProgramResultPayload;
import com.dwinovo.numen.network.payload.RunProgramPayload;
import com.dwinovo.numen.network.payload.StopProgramPayload;
import com.dwinovo.numen.script.Modules;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 程序整段上行、回执下行、反向请求用的五个包与它们的内容:编码再解码是同一个包;装不下的回执换成同一段程序的一条失败;客户端的
 * "送过哪些模块正文"只带没送过的。
 */
@Tag("mc")
class ProgramWireTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static <T> T wire(StreamCodec<ByteBuf, T> codec, T payload) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, payload);
        assertTrue(buf.readableBytes() > 0);
        T back = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "the whole payload was read back");
        return back;
    }

    private static ModuleSet modules() {
        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("my.pit", "aaaaaaaaaaaa");
        manifest.put("numen.work", "bbbbbbbbbbbb");
        return new ModuleSet(manifest, Map.of("aaaaaaaaaaaa", "-- A pit.\nreturn {}"));
    }

    @Test
    void theProgramGoesUpWithHerModulesAndComesBackTheSame() {
        RunProgramPayload run = new RunProgramPayload(A, "call_1", "return numen.status.self()", modules());
        assertEquals(run, wire(RunProgramPayload.STREAM_CODEC, run));
        assertTrue(Wire.TO_SERVER.holds(run.size()));
    }

    /** 超过一个包、没超过整条消息上限的程序照样送(分成片),只有连整条消息都装不下的才由客户端就地回失败。 */
    @Test
    void aProgramBeyondOnePacketIsStillCarriedAndOnlyOneBeyondTheWholeMessageIsAnsweredOnTheClient() {
        RunProgramPayload long_ = new RunProgramPayload(A, "call_2", "-- " + "x".repeat(Wire.TO_SERVER.bytes()), modules());
        assertFalse(Wire.TO_SERVER.holds(long_.size()));
        assertTrue(Wire.TO_SERVER.carries(long_.size()));

        RunProgramPayload huge = new RunProgramPayload(A, "call_3", "-- " + "x".repeat(Wire.MESSAGE_BYTES), modules());
        assertFalse(Wire.TO_SERVER.carries(huge.size()));
        String words = RunProgramPayload.tooBigWords(huge.size());
        assertTrue(words.startsWith("This program with the modules it needs came to "), words);
        assertTrue(words.contains("more than the 8388608 bytes one message to the server can carry, so it was not sent."),
                words);
    }

    @Test
    void theReceiptComesDownWithEveryCallsOutcomeAndTheModulesUsed() {
        Program.Outcome outcome = new Program.Outcome("{\"success\":false,\"message\":\"The script stopped at line 2\"}",
                new ScriptCall.Ending(ScriptCall.Status.STOPPED, null),
                List.of(new ScriptCall.Called("numen.work.dig", "numen.work.dig(7, \"x\", {count = 2})", null, null),
                        new ScriptCall.Called("numen.api.help", "numen.api.help(\"numen.work\")", "numen.work", "no_path")),
                List.of(new Program.Used("numen.work", new ScriptCall.Tally(false, 3, "boom"))),
                "your owner spoke; t4 keeps running");
        ProgramResultPayload down = new ProgramResultPayload(A, "call_1", new RunResult.Ended(outcome).toJson());
        ProgramResultPayload back = wire(ProgramResultPayload.STREAM_CODEC, down);
        assertEquals(new RunResult.Ended(outcome), RunResult.fromJson(back.resultJson()),
                "each call keeps its recorded text, its first name and its kind; the stop keeps its reason and how it ended");
    }

    @Test
    void theReturnedValueAndTheFullErrorStayOnTheServer() {
        Program.Outcome ran = new Program.Outcome("{\"success\":false,\"message\":\"The script stopped at line 1\"}",
                new ScriptCall.Ending(ScriptCall.Status.ERROR, "no_path"), List.of(), List.of(), null,
                List.of(1L, 2L, 3L), Map.of("kind", "no_path", "message", "m", "hint", "h"));
        Program.Outcome arrived = ((RunResult.Ended) RunResult.fromJson(new RunResult.Ended(ran).toJson())).outcome();
        assertEquals(ran.receipt(), arrived.receipt());
        assertEquals(new ScriptCall.Ending(ScriptCall.Status.ERROR, "no_path"), arrived.ending());
        assertEquals(null, arrived.returned(), "the returned value is not sent up");
        assertEquals(null, arrived.failure(), "nor the full error value");
        assertFalse(new RunResult.Ended(ran).toJson().contains("returned"));
    }

    @Test
    void whatTheServerStillLacksComesDownAsTheListOfFingerprints() {
        RunResult missing = new RunResult.Missing(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"));
        assertEquals(missing, RunResult.fromJson(missing.toJson()));
    }

    /** 按构造有界:最坏的一段(两百次调用带着最长的调用文字,stderr、返回值、stdout 都写满)远小于一个下行包。 */
    @Test
    void theWorstReceiptThatCanBeWrittenIsUnderHalfOfOneDownwardPayload() {
        String longest = "x".repeat(com.dwinovo.numen.agent.script.ScriptLimits.CALL_TEXT_CHARS + 20);
        List<ScriptCall.Called> calls = new java.util.ArrayList<>();
        for (int i = 0; i < com.dwinovo.numen.agent.script.ScriptLimits.COMMANDS; i++) {
            calls.add(new ScriptCall.Called("numen.build.place", longest, longest, "bad_argument"));
        }
        String receipt = "y".repeat(com.dwinovo.numen.agent.script.ScriptLimits.STDERR_CHARS
                + com.dwinovo.numen.agent.script.ScriptLimits.RETURNED_CHARS
                + com.dwinovo.numen.agent.script.ScriptLimits.PRINTED_CHARS);
        ProgramResultPayload worst = new ProgramResultPayload(A, "call_9", new RunResult.Ended(
                new Program.Outcome(receipt, new ScriptCall.Ending(ScriptCall.Status.OK, null), calls, List.of(new Program.Used("numen.work",
                        new ScriptCall.Tally(false, 3, "boom"))), "your owner spoke; t1 keeps running")).toJson());
        int size = Wire.size(ProgramResultPayload.STREAM_CODEC, worst, Unpooled::buffer);
        assertTrue(size < Wire.TO_CLIENT.bytes() / 2, "the worst receipt is " + size + " bytes");
        assertEquals(worst, Wire.TO_CLIENT.fit(ProgramResultPayload.STREAM_CODEC, worst, Unpooled::buffer),
                "goes out untouched: there is no shrinking, it is bounded where it is written");
    }

    @Test
    void theReverseRequestAndItsAnswerKeepTheirModules() {
        ClientCallPayload ask = new ClientCallPayload(A, "call_1#2", "numen.module.save", "{\"code\":\"x\"}");
        assertEquals(ask, wire(ClientCallPayload.STREAM_CODEC, ask));
        ClientCallResultPayload plain = new ClientCallResultPayload(A, "call_1#2", "{\"ok\":true}", Optional.empty());
        assertEquals(plain, wire(ClientCallResultPayload.STREAM_CODEC, plain));
        ClientCallResultPayload changed = new ClientCallResultPayload(A, "call_1#2", "{\"ok\":true}", Optional.of(modules()));
        assertEquals(changed, wire(ClientCallResultPayload.STREAM_CODEC, changed));
    }

    @Test
    void theStopKeepsWhichProgramAndHowItStops() {
        StopProgramPayload between = new StopProgramPayload(A, "call_1", false, false, "your owner spoke");
        StopProgramPayload cut = new StopProgramPayload(A, "call_1", true, true, "");
        assertEquals(between, wire(StopProgramPayload.STREAM_CODEC, between));
        assertEquals(cut, wire(StopProgramPayload.STREAM_CODEC, cut));
    }

    // ---- 客户端这一侧"送过哪些模块正文" ----

    @Test
    void theClientSendsATextOnlyOncePerConnectionUnlessTheServerLostIt() {
        String pit = "-- A pit.\nlocal M = {}\nreturn M\n";
        ModuleSync sync = new ModuleSync();
        ModuleSet first = sync.pack(Map.of("my.pit", pit));
        assertEquals(1, first.bodies().size());
        assertEquals(Modules.fingerprint(pit), first.manifest().get("my.pit"));

        ModuleSet second = sync.pack(Map.of("my.pit", pit));
        assertEquals(first.manifest(), second.manifest(), "the list is always whole");
        assertTrue(second.bodies().isEmpty(), "a text already sent is not sent again");

        sync.lost(List.of(Modules.fingerprint(pit)));
        assertEquals(1, sync.pack(Map.of("my.pit", pit)).bodies().size(), "the server said it lacks it: sent again");

        sync.reset();
        assertEquals(1, sync.pack(Map.of("my.pit", pit)).bodies().size(), "a new connection starts from nothing");
    }
}
