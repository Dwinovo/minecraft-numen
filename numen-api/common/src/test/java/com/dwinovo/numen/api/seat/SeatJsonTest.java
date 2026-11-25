package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.SeatPayload;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.script.ScriptCall;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 座位上的消息在线上的样子:事件、程序的结局、答复,以及装进 {@link SeatPayload} 过一遍线。 */
@Tag("mc")
class SeatJsonTest {

    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static Program.Outcome outcome() {
        return new Program.Outcome("{\"success\":false,\"message\":\"line 3: boom\"}",
                new ScriptCall.Ending(ScriptCall.Status.ERROR, "failed"),
                List.of(new ScriptCall.Called("numen.work.dig", "numen.work.dig(\"stone\")", "stone", "no_path"),
                        new ScriptCall.Called("numen.status.self", "numen.status.self()", null, null)),
                List.of(new Program.Used("my.lumber", new ScriptCall.Tally(false, 3, "boom")),
                        new Program.Used("numen.work", new ScriptCall.Tally(true, 0, null))),
                "your owner spoke; t8 keeps running");
    }

    @Test
    void aProgramResultComesBackEqualAndDoesNotCarryTheValuesThatStayInTheProcess() {
        ProgramResult result = new ProgramResult("p1", outcome(), 4, "<runtime_state/>", 77);
        assertEquals(result, ProgramResult.fromJson(result.toJson()));
        Program.Outcome inProcess = new Program.Outcome("{\"success\":true,\"message\":\"m\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), List.of(), null, 42, java.util.Map.of());
        JsonObject wire = ProgramResult.outcomeJson(inProcess);
        assertTrue(!wire.has("returned") && !wire.has("failure"), wire.toString());
    }

    @Test
    void anEventComesBackEqualWithItsResultAndOutcome() {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        SeatEvent plain = SeatEvent.of(HER, 3, new EventQueue.Entry("task_finished", "<event kind=\"task_finished\"/>", 9L,
                true, result), "<runtime_state/>", 12).withSeq(5);
        assertEquals(plain, SeatEvent.fromJson(plain.toJson()));
        SeatEvent stopped = SeatEvent.ofOutcome(HER, 3, new EventQueue.Entry("program_stopped", "cut", 9L, false),
                outcome(), "", 13).withSeq(6);
        assertEquals(stopped, SeatEvent.fromJson(stopped.toJson()));
    }

    @Test
    void openedComesBackEqual() {
        Opened opened = new Opened(2, "abc", 9, 4, true);
        assertEquals(opened, Opened.fromJson(opened.toJson()));
    }

    @Test
    void aMessageSurvivesTheWireInBothDirectionsIncludingWhenItIsCutIntoFragments() throws Exception {
        RpcMessage small = new RpcMessage.Notification("events/new", new JsonObject());
        RpcMessage big = new RpcMessage.Response(new JsonPrimitive(7), new JsonPrimitive("z".repeat(400_000)), null);
        for (Wire direction : Wire.values()) {
            for (RpcMessage message : List.of(small, big)) {
                SeatPayload sent = SeatPayload.frame(direction, message);
                assertEquals(message, Fragments.crossed(direction, SeatPayload.codecOf(direction), sent).message());
            }
        }
        assertTrue(Fragments.packets(Wire.TO_SERVER, SeatPayload.TO_SERVER_CODEC, SeatPayload.frame(Wire.TO_SERVER, big),
                io.netty.buffer.Unpooled::buffer).size() > 1, "the long one went up in fragments");
    }

    @Test
    void aMessageOverTheWholeMessageLimitIsNotFramed() {
        RpcMessage tooBig = new RpcMessage.Response(new JsonPrimitive(1),
                new JsonPrimitive("z".repeat(Wire.MESSAGE_BYTES)), null);
        IOException refused = assertThrows(IOException.class, () -> SeatPayload.frame(Wire.TO_CLIENT, tooBig));
        assertTrue(refused.getMessage().contains("more than the " + Wire.MESSAGE_BYTES + " bytes")
                && refused.getMessage().contains("so it was not sent"), refused.getMessage());
    }
}
