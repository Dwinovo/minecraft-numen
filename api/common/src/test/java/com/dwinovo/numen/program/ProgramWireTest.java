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

    @Test
    void aProgramThatDoesNotFitOneUpwardPayloadIsAnsweredOnTheClientWithHowBigItWas() {
        RunProgramPayload huge = new RunProgramPayload(A, "call_2", "-- " + "x".repeat(Wire.TO_SERVER.bytes()), modules());
        assertFalse(Wire.TO_SERVER.holds(huge.size()));
        String words = RunProgramPayload.tooBigWords(huge.size());
        assertTrue(words.startsWith("This program with the modules it needs came to "), words);
        assertTrue(words.contains("more than the 1048576 bytes one message to the server can carry, so it was not sent."),
                words);
    }

    @Test
    void theReceiptComesDownWithEveryCallsOutcomeAndTheModulesUsed() {
        Program.Outcome outcome = new Program.Outcome("{\"success\":true,\"message\":\"The script ran to the end\"}",
                List.of(new ScriptCall.Called("numen.work.dig", List.of(7L, "x"), Map.of("count", 2L), null),
                        new ScriptCall.Called("numen.move.go", List.of(), Map.of(), "no_path")),
                List.of(new Program.Used("numen.work", new ScriptCall.Tally(false, 3, "boom"))));
        ProgramResultPayload down = new ProgramResultPayload(A, "call_1", new RunResult.Ended(outcome).toJson());
        ProgramResultPayload back = wire(ProgramResultPayload.STREAM_CODEC, down);
        assertEquals(new RunResult.Ended(outcome), RunResult.fromJson(back.resultJson()),
                "the outcomes keep their arguments as the program wrote them: numbers stay numbers");
    }

    @Test
    void whatTheServerStillLacksComesDownAsTheListOfFingerprints() {
        RunResult missing = new RunResult.Missing(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"));
        assertEquals(missing, RunResult.fromJson(missing.toJson()));
    }

    @Test
    void aReceiptTooBigForOneDownwardPayloadBecomesAFailureForTheSameProgram() {
        String huge = new RunResult.Ended(new Program.Outcome("{\"success\":true,\"message\":\""
                + "y".repeat(Wire.TO_CLIENT.bytes() + 10) + "\"}", List.of(), List.of())).toJson();
        ProgramResultPayload sent = Wire.TO_CLIENT.fit(ProgramResultPayload.STREAM_CODEC,
                new ProgramResultPayload(A, "call_9", huge), Unpooled::buffer);
        assertEquals("call_9", sent.programId());
        String receipt = ((RunResult.Ended) RunResult.fromJson(sent.resultJson())).outcome().receipt();
        assertTrue(receipt.contains("The receipt of this program came to "), receipt);
        assertTrue(receipt.contains("Have the program return or print less"), receipt);
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
