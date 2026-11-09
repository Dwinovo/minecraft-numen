package com.dwinovo.numen.mcp.rpc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** SSE 的写与读照 WHATWG 的事件流格式:多行 data、注释、编号与重连间隔、没有数据的预备事件、各种换行。 */
class SseTest {

    private static Sse.Reader reader(String stream) {
        return new Sse.Reader(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void whatIsWrittenIsReadBackIncludingDataWithLineBreaks() throws Exception {
        String stream = Sse.event("{\"a\":1}") + Sse.comment("keepalive") + Sse.event("line one\nline two");
        try (Sse.Reader reader = reader(stream)) {
            assertEquals(new Sse.Event("message", "{\"a\":1}"), reader.next());
            assertEquals(new Sse.Event("message", "line one\nline two"), reader.next(), "注释不是事件;多行 data 按换行连回去");
            assertNull(reader.next());
        }
    }

    @Test
    void aPrimingEventOnlyUpdatesTheIdAndRetryAndEveryLineEndingIsALineEnding() throws Exception {
        String stream = "id: e-1\r\nretry: 500\r\ndata: \r\n\r\n" + "id:e-2\rdata:{\"b\":2}\r\r" + "event: other\ndata: x\n\n";
        try (Sse.Reader reader = reader(stream)) {
            assertEquals(new Sse.Event("message", "{\"b\":2}"), reader.next(), "data 为空的预备事件不交出来");
            assertEquals("e-2", reader.lastEventId());
            assertEquals(500, reader.retry());
            assertEquals(new Sse.Event("other", "x"), reader.next());
            assertNull(reader.next());
        }
    }

    @Test
    void anUnfinishedEventAtTheEndIsDropped() throws Exception {
        try (Sse.Reader reader = reader("data: half")) {
            assertNull(reader.next(), "没有空行收尾的事件照标准丢掉");
        }
    }
}
