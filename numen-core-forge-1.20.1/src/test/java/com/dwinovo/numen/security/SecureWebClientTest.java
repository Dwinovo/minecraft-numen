package com.dwinovo.numen.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SecureWebClientTest {
    @Test void readsChunkedResponseWithoutBufferingPastFraming() throws Exception {
        String response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        assertEquals("hello world", new String(SecureWebClient.readResponseBodyForTest(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII))), StandardCharsets.UTF_8));
    }

    @Test void rejectsEncodedBodyPastLimitDuringRead() {
        byte[] body = new byte[SecureWebClient.MAX_ENCODED_BYTES + 1];
        byte[] header = ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] response = Arrays.copyOf(header, header.length + body.length);
        assertThrows(IOException.class, () -> SecureWebClient.readResponseBodyForTest(new ByteArrayInputStream(response)));
    }

    @Test void rejectsGzipBombByDecodedLimit() throws Exception {
        byte[] expanded = new byte[SecureWebClient.MAX_DECODED_BYTES + 1];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(expanded); }
        byte[] header = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] response = Arrays.copyOf(header, header.length + compressed.size());
        System.arraycopy(compressed.toByteArray(), 0, response, header.length, compressed.size());
        assertThrows(IOException.class, () -> SecureWebClient.readResponseBodyForTest(new ByteArrayInputStream(response)));
    }

    @Test void rejectsUnknownContentEncoding() {
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Encoding: br\r\n\r\ndata".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> SecureWebClient.readResponseBodyForTest(new ByteArrayInputStream(response)));
    }

    @Test void rejectsMalformedOrOversizedChunks() {
        byte[] malformed = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nnot-hex\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] oversized = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(SecureWebClient.MAX_ENCODED_BYTES + 1) + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> SecureWebClient.readResponseBodyForTest(new ByteArrayInputStream(malformed)));
        assertThrows(IOException.class, () -> SecureWebClient.readResponseBodyForTest(new ByteArrayInputStream(oversized)));
    }

    @Test void zeroChunkAtEofTerminatesWithoutLooping() throws Exception {
        byte[] response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(new byte[0], SecureWebClient.readResponseBodyForTest(new ByteArrayInputStream(response)));
    }
}
