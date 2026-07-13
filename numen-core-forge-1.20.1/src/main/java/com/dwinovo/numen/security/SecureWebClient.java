package com.dwinovo.numen.security;

import com.dwinovo.numen.agent.http.LlmHttpException;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** DNS-pinned, bounded HTTP/1.1 public-web fetcher with per-redirect revalidation. */
public final class SecureWebClient {
    public static final int MAX_ENCODED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_DECODED_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 256_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private final ProxyAddress proxy;

    public SecureWebClient(String proxy) { this.proxy = parseProxy(proxy); }
    public SecureWebClient() { this(""); }

    public CompletableFuture<String> getText(String rawUrl, Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (proxy != null) throw new IOException("secure public search does not support proxies because the target IP cannot be pinned end-to-end");
                return fetch(PublicUrlPolicy.resolve(rawUrl), timeout == null ? Duration.ofSeconds(25) : timeout, 0);
            }
            catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
        });
    }

    private String fetch(PublicUrlPolicy.ResolvedTarget target, Duration timeout, int redirects) throws IOException {
        RawResponse response = request(target, timeout);
        if (response.status >= 300 && response.status < 400) {
            if (redirects >= MAX_REDIRECTS) throw new IOException("too many redirects");
            String location = response.headers.getOrDefault("location", "");
            if (location.isBlank()) throw new IOException("redirect has no location");
            return fetch(PublicUrlPolicy.resolve(target.uri().resolve(location)), timeout, redirects + 1);
        }
        String text = decode(response.body, response.headers.getOrDefault("content-type", ""));
        if (response.status / 100 != 2) throw new LlmHttpException(response.status, truncate(text, 16_384));
        return truncate(text, MAX_TEXT_CHARS);
    }

    private RawResponse request(PublicUrlPolicy.ResolvedTarget target, Duration timeout) throws IOException {
        IOException last = null;
        for (InetAddress address : target.addresses()) {
            try (Socket socket = connect(address, target, timeout)) {
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis())));
                OutputStream out = socket.getOutputStream();
                URI uri = target.uri();
                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                String hostHeader = target.host() + ((uri.getPort() >= 0) ? ":" + target.port() : "");
                String request = "GET " + path + " HTTP/1.1\r\nHost: " + hostHeader
                        + "\r\nAccept: text/html,application/xhtml+xml,text/plain,application/rss+xml;q=0.9"
                        + "\r\nAccept-Encoding: gzip, deflate\r\nUser-Agent: Numen-Minecraft/1.20.1"
                        + "\r\nConnection: close\r\n\r\n";
                out.write(request.getBytes(StandardCharsets.US_ASCII)); out.flush();
                return readResponse(socket.getInputStream());
            } catch (IOException error) { last = error; }
        }
        throw last == null ? new IOException("no validated target address") : last;
    }

    private Socket connect(InetAddress pinned, PublicUrlPolicy.ResolvedTarget target, Duration timeout) throws IOException {
        int connectTimeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis()));
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(pinned, target.port()), connectTimeout);
        if (!"https".equalsIgnoreCase(target.uri().getScheme())) return plain;
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, target.host(), target.port(), true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new SNIHostName(target.host())));
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    private static RawResponse readResponse(InputStream raw) throws IOException {
        String statusLine = readLine(raw, MAX_HEADER_BYTES);
        if (statusLine == null || !statusLine.startsWith("HTTP/")) throw new IOException("invalid HTTP response");
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) throw new IOException("invalid HTTP status");
        int status = Integer.parseInt(parts[1]);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        int used = statusLine.length(); String line;
        while ((line = readLine(raw, MAX_HEADER_BYTES - used)) != null && !line.isEmpty()) {
            used += line.length(); int colon = line.indexOf(':');
            if (colon > 0) headers.merge(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim(), (a, b) -> a + "," + b);
        }
        InputStream framed = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")
                ? new ChunkedInputStream(raw) : raw;
        CountingInputStream encoded = new CountingInputStream(framed, MAX_ENCODED_BYTES);
        String encoding = headers.getOrDefault("content-encoding", "identity").trim().toLowerCase(Locale.ROOT);
        InputStream decoded = switch (encoding) {
            case "", "identity" -> encoded;
            case "gzip" -> new GZIPInputStream(encoded);
            case "deflate" -> new InflaterInputStream(encoded);
            default -> throw new IOException("unsupported content encoding: " + encoding);
        };
        byte[] body = readLimited(decoded, MAX_DECODED_BYTES);
        return new RawResponse(status, Map.copyOf(headers), body);
    }

    static byte[] readResponseBodyForTest(InputStream raw) throws IOException { return readResponse(raw).body(); }

    private static byte[] readLimited(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 16_384));
        byte[] buffer = new byte[8192]; int total = 0, read;
        while ((read = input.read(buffer)) >= 0) {
            total += read; if (total > max) throw new IOException("decoded response exceeds size limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream input, int remaining) throws IOException {
        if (remaining <= 0) throw new IOException("HTTP headers exceed size limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int previous = -1, value;
        while ((value = input.read()) >= 0) {
            if (out.size() >= remaining) throw new IOException("HTTP line exceeds size limit");
            if (previous == '\r' && value == '\n') { byte[] bytes = out.toByteArray(); return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1); }
            out.write(value); previous = value;
        }
        return out.size() == 0 ? null : out.toString(StandardCharsets.ISO_8859_1);
    }

    private static String decode(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        for (String part : contentType.split(";")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("charset=")) try { charset = Charset.forName(value.substring(8).replace("\"", "")); }
            catch (RuntimeException ignored) { charset = StandardCharsets.UTF_8; }
        }
        return new String(body, charset);
    }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    private static ProxyAddress parseProxy(String proxy) {
        if (proxy == null || proxy.isBlank()) return null;
        String value = proxy.trim().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "").replaceFirst("/.*$", "");
        int colon = value.lastIndexOf(':'); if (colon <= 0) throw new IllegalArgumentException("proxy must be host:port");
        int port = Integer.parseInt(value.substring(colon + 1));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid proxy port");
        return new ProxyAddress(value.substring(0, colon), port);
    }

    private record ProxyAddress(String host, int port) { }
    private record RawResponse(int status, Map<String, String> headers, byte[] body) { }

    private static final class CountingInputStream extends FilterInputStream {
        private final long max; private long count;
        CountingInputStream(InputStream in, long max) { super(in); this.max = max; }
        public int read() throws IOException { int v = super.read(); if (v >= 0) add(1); return v; }
        public int read(byte[] b, int o, int l) throws IOException { int n = super.read(b, o, l); if (n > 0) add(n); return n; }
        private void add(int n) throws IOException { count += n; if (count > max) throw new IOException("encoded response exceeds size limit"); }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream in; private long remaining; private boolean done;
        ChunkedInputStream(InputStream in) { this.in = in; }
        public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (remaining == 0) {
                String line = readLine(in, 128); if (line == null) throw new EOFException("missing chunk size");
                int semicolon = line.indexOf(';'); if (semicolon >= 0) line = line.substring(0, semicolon);
                try { remaining = Long.parseLong(line.trim(), 16); }
                catch (NumberFormatException error) { throw new IOException("invalid chunk size", error); }
                if (remaining < 0 || remaining > MAX_ENCODED_BYTES) throw new IOException("chunk exceeds size limit");
                if (remaining == 0) {
                    done = true;
                    String trailer;
                    while ((trailer = readLine(in, MAX_HEADER_BYTES)) != null && !trailer.isEmpty()) { }
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, remaining)); if (n < 0) throw new EOFException("truncated chunk");
            remaining -= n; if (remaining == 0 && !Objects.equals(readLine(in, 2), "")) throw new IOException("invalid chunk delimiter");
            return n;
        }
    }
}
