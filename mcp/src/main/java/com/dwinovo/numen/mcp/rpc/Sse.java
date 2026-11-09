package com.dwinovo.numen.mcp.rpc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Server-Sent Events 的写与读(WHATWG HTML 的 event stream 格式),服务端写流、客户端读流都用这一份。MCP 在每个事件的 data 里放一条
 * JSON-RPC 消息。
 */
public final class Sse {

    private Sse() {}

    /** 一个事件:{@code data} 是各 data 行以换行连起来的文本。 */
    public record Event(String type, String data) {}

    /** 一条消息写成一个事件。{@code data} 里有换行的拆成几行 data(读的一方按换行连回去)。 */
    public static String event(String data) {
        StringBuilder sb = new StringBuilder("event: message\n");
        for (String line : data.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        return sb.append('\n').toString();
    }

    /** 一行注释:读的一方忽略它,用来让一条久不说话的流仍有字节过去(写不出去就是对方走了)。 */
    public static String comment(String text) {
        return ": " + text + "\n\n";
    }

    /**
     * 读一条事件流。除了事件本身,还记着流给的最后一个事件编号({@link #lastEventId},断了以后带着它接续)与重连间隔({@link #retry})。
     */
    public static final class Reader implements AutoCloseable {
        private final BufferedReader in;
        private String lastEventId;
        private long retry = -1;

        public Reader(InputStream in) {
            this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        /** 下一个有数据的事件;流读完了是 null。只有编号或重连间隔、没有数据的事件(预备事件)不交出来,只更新这两样。 */
        public Event next() throws IOException {
            StringBuilder data = null;
            String type = "";
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    // 空行收一个事件;data 去掉末尾那个换行后是空的不交出来(标准如此)
                    String text = data == null ? "" : data.substring(0, data.length() - 1);
                    if (!text.isEmpty()) {
                        return new Event(type.isEmpty() ? "message" : type, text);
                    }
                    data = null;
                    type = "";
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "data" -> {
                        if (data == null) {
                            data = new StringBuilder();
                        }
                        data.append(value).append('\n');
                    }
                    case "event" -> type = value;
                    case "id" -> {
                        if (value.indexOf('\0') < 0) {
                            lastEventId = value;
                        }
                    }
                    case "retry" -> {
                        if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                            retry = Long.parseLong(value);
                        }
                    }
                    default -> { }   // 不认识的字段照标准忽略
                }
            }
            return null;
        }

        /** 流给过的最后一个事件编号;没给过是 null。 */
        public String lastEventId() {
            return lastEventId;
        }

        /** 流给的重连间隔(毫秒);没给过是 -1。 */
        public long retry() {
            return retry;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
