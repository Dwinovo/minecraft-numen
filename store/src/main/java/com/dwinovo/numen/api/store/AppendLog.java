package com.dwinovo.numen.api.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 只追加的日志:一行一条 JSON 记录。本类不认识记录里装的是什么,只管下面这些。
 *
 * <ul>
 *   <li><b>头</b>:首行 {@code {"type":"header","v":<版本>,"created":<毫秒>}}。没有头的文件是第 1 版。</li>
 *   <li><b>记录</b>:每条有 {@code type}、稳定的 {@code id}(文件内唯一)、{@code parentId}(上一条的 id,第一条是 null)
 *       和 {@code ts};其余字段是上层的。追加只往文件尾加一行,已写的行不动。</li>
 *   <li><b>未知类型</b>:{@link #read} 把所有带类型的记录都交出来,认不认得由上层定;不认得的上层跳过,
 *       它们仍在文件里(追加不碰、整份迁移原样带过),新版本加的类型旧版本不会弄丢。</li>
 *   <li><b>坏行</b>:不是 JSON 对象、没有类型的行,读时跳过(数在 {@link Loaded#skipped()})。
 *       文件尾被撕掉半行时,下一次追加先补一个换行,不让新记录接在半行后面一起坏掉。</li>
 *   <li><b>换版本</b>:打开时发现文件版本比当前旧,就把所有记录依次交给 {@link Format} 里的升级步骤,
 *       升完先写临时文件、原子替换原文件,替换之前留一份 {@code <文件>.v<旧版本>.bak}。
 *       升级出错或写不下,原文件一个字节不动,下次打开重来。比当前新的文件(回退了模组)不重写,照常读写。</li>
 *   <li><b>整份重写</b>:{@link #rewrite} 走同一条"临时文件换进去"的路,给读后即清的队列用。</li>
 * </ul>
 *
 * <p>磁盘上的写交给 {@link Io}(调用线程或使用方给的后台执行器);id 与顺序在调用线程上就定下来。
 * 一份日志只有一个写入方,不加文件锁。
 */
public final class AppendLog {

    /** 头记录的类型。 */
    public static final String HEADER = "header";
    /** 头里版本号的键。 */
    public static final String VERSION = "v";

    private static final Set<String> RESERVED = Set.of("type", "id", "parentId", "ts");

    /**
     * 把第 n 版的全部记录(不含头)升成第 n+1 版。记录可以改、删、增、重排;
     * 缺 {@code id}/{@code parentId} 的由本类在最后一步补齐。
     */
    @FunctionalInterface
    public interface Upgrade {
        List<JsonObject> apply(List<JsonObject> records);
    }

    /** 当前版本,以及从每个旧版本往上升的那一步。 */
    public static final class Format {
        private final int current;
        private final Map<Integer, Upgrade> steps = new HashMap<>();

        private Format(int current) {
            if (current < 1) {
                throw new IllegalArgumentException("版本从 1 起");
            }
            this.current = current;
        }

        /** 当前是第 {@code current} 版;每个旧版本都要用 {@link #upgrade} 补上它往上升的那一步。 */
        public static Format of(int current) {
            return new Format(current);
        }

        public Format upgrade(int from, Upgrade step) {
            steps.put(from, step);
            return this;
        }

        public int current() {
            return current;
        }

        void check() {
            for (int v = 1; v < current; v++) {
                if (!steps.containsKey(v)) {
                    throw new IllegalStateException("缺第 " + v + " 版往上升的那一步");
                }
            }
        }
    }

    /** 一条记录:整行 JSON,加上几个每条都有的字段的读法。别改里面的对象。 */
    public record Record(JsonObject json) {

        public String type() {
            return string("type");
        }

        public String id() {
            return string("id");
        }

        public String parentId() {
            return string("parentId");
        }

        /** 记下的时刻(毫秒);没有则 0。 */
        public long ts() {
            return json.has("ts") && json.get("ts").isJsonPrimitive() ? json.get("ts").getAsLong() : 0L;
        }

        /** 字符串字段;没有或不是字符串是 null。 */
        public String string(String key) {
            JsonElement el = json.get(key);
            return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
        }
    }

    /** 读出来的记录(文件顺序),以及跳过的坏行数。 */
    public record Loaded(List<Record> records, int skipped) {}

    /** 往前读时每次读多少字节。 */
    private static final int BACKWARD_CHUNK = 64 * 1024;

    private final Path file;
    private final Format format;
    private final Io io;

    // 以下四项在 open() 里从文件建起,之后随追加更新;访问都在 this 的锁里
    private boolean opened;
    private final Set<String> ids = new HashSet<>();
    private String leaf;
    private boolean endsWithNewline = true;
    private long created;

    public AppendLog(Path file, Format format, Io io) {
        format.check();
        this.file = file;
        this.format = format;
        this.io = io;
    }

    public Path file() {
        return file;
    }

    // ---- 打开(读头、必要时迁移、建 id 索引) ----

    /**
     * 打开:读头;版本旧了就整份迁移;建好 id 索引。幂等,读和写都会先走它,想让迁移的错在启动时就暴露可以显式调一次。
     * 迁移失败抛 {@link StoreException},文件原样,下次再调会重来。
     */
    public synchronized void open() {
        if (opened) {
            return;
        }
        if (!Files.isRegularFile(file)) {
            created = System.currentTimeMillis();
            opened = true;
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StoreException("读不了 " + file + ": " + e, e);
        }
        JsonObject head = null;
        List<JsonObject> records = new ArrayList<>();
        boolean first = true;
        for (String line : (Iterable<String>) text.lines()::iterator) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject o = parse(line);
            if (o == null) {
                first = false;
                continue;   // 坏行:读时本来就跳过,迁移重写时不再带进新文件(原文在备份里)
            }
            if (HEADER.equals(typeOf(o))) {
                if (first) {
                    head = o;
                }
                first = false;
                continue;
            }
            first = false;
            records.add(o);
        }
        int from = head != null && head.has(VERSION) ? head.get(VERSION).getAsInt() : 1;
        created = head != null && head.has("created") ? head.get("created").getAsLong() : fileTime();
        if (from < format.current) {
            migrate(records, from);
        } else {
            index(records);
            endsWithNewline = text.isEmpty() || text.endsWith("\n");
        }
        opened = true;
    }

    private void migrate(List<JsonObject> records, int from) {
        List<JsonObject> chained;
        try {
            List<JsonObject> upgraded = records;
            for (int v = from; v < format.current; v++) {
                upgraded = format.steps.get(v).apply(upgraded);
            }
            chained = chain(upgraded);
        } catch (RuntimeException e) {
            throw new StoreException("升级 " + file + " 失败(从第 " + from + " 版): " + e, e);
        }
        Path bak = file.resolveSibling(file.getFileName() + ".v" + from + ".bak");
        try {
            if (!Files.exists(bak)) {
                Files.copy(file, bak);
            }
        } catch (IOException e) {
            throw new StoreException("留不了备份 " + bak + ": " + e, e);
        }
        Atomic.write(file, render(chained));
        index(chained);
        endsWithNewline = true;
    }

    private void index(List<JsonObject> records) {
        ids.clear();
        leaf = null;
        for (JsonObject o : records) {
            String id = idOf(o);
            if (id != null) {
                ids.add(id);
            }
            leaf = id;
        }
    }

    // ---- 读 ----

    /** 读全部记录(文件顺序,不含头)。读不了抛 {@link StoreException};文件不在是空。 */
    public Loaded read() {
        open();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException absent) {
            return new Loaded(List.of(), 0);
        } catch (IOException e) {
            throw new StoreException("读不了 " + file + ": " + e, e);
        }
        List<Record> out = new ArrayList<>();
        int skipped = 0;
        for (String line : (Iterable<String>) text.lines()::iterator) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject o = parse(line);
            String type = o == null ? null : typeOf(o);
            if (type == null) {
                skipped++;
            } else if (!HEADER.equals(type)) {
                out.add(new Record(o));
            }
        }
        return new Loaded(out, skipped);
    }

    /** 交给执行器去读。 */
    public CompletableFuture<Loaded> readAsync() {
        return io.read(this::read);
    }

    /**
     * 从文件尾往前读:{@code beforeId} 那条之前的最多 {@code limit} 条记录(文件顺序,不含头),{@code beforeId} 为 null 就是
     * 最后 {@code limit} 条。给"分页往前翻"用,只读到够数为止,不读整份。{@code beforeId} 不在文件里得空表(不退化成最后一页:
     * 翻页的游标指到一条已经不存在的记录,是调用方的状态错了,不该悄悄给它另一页)。读不了抛 {@link StoreException}。
     */
    public Loaded readBefore(String beforeId, int limit) {
        open();
        if (limit <= 0 || !Files.isRegularFile(file)) {
            return new Loaded(List.of(), 0);
        }
        List<Record> newestFirst = new ArrayList<>();
        int skipped = 0;
        boolean[] passed = {beforeId == null};
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long position = channel.size();
            byte[] carry = new byte[0];
            while (position > 0 && newestFirst.size() < limit) {
                int size = (int) Math.min(BACKWARD_CHUNK, position);
                position -= size;
                ByteBuffer chunk = ByteBuffer.allocate(size + carry.length);
                chunk.limit(size);
                while (chunk.hasRemaining()) {
                    if (channel.read(chunk, position + chunk.position()) < 0) {
                        throw new IOException("文件在读的时候变短了");
                    }
                }
                chunk.limit(chunk.capacity());
                chunk.put(carry);
                byte[] data = chunk.array();
                int end = data.length;
                for (int i = data.length - 1; i >= 0 && newestFirst.size() < limit; i--) {
                    if (data[i] == '\n') {
                        skipped += takeBackward(data, i + 1, end, beforeId, passed, newestFirst);
                        end = i;
                    }
                }
                carry = java.util.Arrays.copyOfRange(data, 0, end);
            }
            if (position == 0 && newestFirst.size() < limit) {
                skipped += takeBackward(carry, 0, carry.length, beforeId, passed, newestFirst);
            }
        } catch (IOException e) {
            throw new StoreException("读不了 " + file + ": " + e, e);
        }
        java.util.Collections.reverse(newestFirst);
        return new Loaded(newestFirst, skipped);
    }

    /** 交给执行器去读。 */
    public CompletableFuture<Loaded> readBeforeAsync(String beforeId, int limit) {
        return io.read(() -> readBefore(beforeId, limit));
    }

    /** 往前读时处理一行:还没走过 {@code beforeId} 的丢掉,走过了的收进来;返回这行是不是坏行。 */
    private static int takeBackward(byte[] data, int from, int to, String beforeId, boolean[] passed,
                                    List<Record> out) {
        if (to <= from) {
            return 0;
        }
        String line = new String(data, from, to - from, StandardCharsets.UTF_8);
        if (line.isBlank()) {
            return 0;
        }
        JsonObject o = parse(line);
        String type = o == null ? null : typeOf(o);
        if (type == null) {
            return passed[0] ? 1 : 0;
        }
        if (HEADER.equals(type)) {
            return 0;
        }
        if (!passed[0]) {
            passed[0] = beforeId.equals(idOf(o));
            return 0;
        }
        out.add(new Record(o));
        return 0;
    }

    // ---- 写 ----

    /**
     * 追加一条记录:类型、新 id、上一条的 id、此刻的时间由本类盖上,{@code body} 是其余字段(不许用这四个键)。
     * 返回写下的这条;磁盘上的写失败交给 {@link Io#onError()},返回的记录照样给(调用方这一局还得看得见刚发生的事)。
     * 日志打不开(迁移失败)时不写盘,以免新记录混进还没升级的文件。
     */
    public Record append(String type, JsonObject body) {
        for (String key : RESERVED) {
            if (body.has(key)) {
                throw new IllegalArgumentException("记录体里不许用 " + key);
            }
        }
        JsonObject record = new JsonObject();
        String line = null;
        boolean writable = true;
        synchronized (this) {
            try {
                open();
            } catch (StoreException e) {
                io.onError().accept(e);
                writable = false;
            }
            String id = freshId(ids);
            record.addProperty("type", type);
            record.addProperty("id", id);
            record.add("parentId", leaf == null ? JsonNull.INSTANCE : new JsonPrimitive(leaf));
            record.addProperty("ts", System.currentTimeMillis());
            body.entrySet().forEach(e -> record.add(e.getKey(), e.getValue()));
            if (writable) {
                line = (endsWithNewline ? "" : "\n") + record + "\n";
                endsWithNewline = true;
                ids.add(id);
                leaf = id;
            }
        }
        if (writable) {
            String toWrite = line;
            io.write(() -> appendLine(toWrite));
        }
        return new Record(record);
    }

    /**
     * 整份换成 {@code records}(每个要有 {@code type},缺 id 的补上,{@code parentId} 按顺序重接);空表就删文件。
     * 给"读后即清"的队列用——日志的主路是 {@link #append}。和迁移一样先写临时文件再原子替换。
     */
    public void rewrite(List<JsonObject> records) {
        String text;
        synchronized (this) {
            try {
                open();
            } catch (StoreException e) {
                io.onError().accept(e);
                return;
            }
            List<JsonObject> chained = chain(records);
            index(chained);
            endsWithNewline = true;
            text = chained.isEmpty() ? null : render(chained);
        }
        if (text == null) {
            io.write(this::deleteFile);
        } else {
            String toWrite = text;
            io.write(() -> Atomic.write(file, toWrite));
        }
    }

    private void appendLine(String line) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            String text = Files.exists(file) ? line : header() + "\n" + line;
            Files.writeString(file, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StoreException("追加不进 " + file + ": " + e, e);
        }
    }

    private void deleteFile() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StoreException("删不了 " + file + ": " + e, e);
        }
    }

    // ---- 小件 ----

    private String header() {
        JsonObject h = new JsonObject();
        h.addProperty("type", HEADER);
        h.addProperty(VERSION, format.current);
        h.addProperty("created", created);
        return h.toString();
    }

    private String render(List<JsonObject> records) {
        StringBuilder sb = new StringBuilder(header()).append('\n');
        for (JsonObject o : records) {
            sb.append(o).append('\n');
        }
        return sb.toString();
    }

    /** 补齐 id、按顺序接好 parentId;字段顺序统一成 type、id、parentId、ts、其余。原对象不动。 */
    private static List<JsonObject> chain(List<JsonObject> records) {
        Set<String> used = new HashSet<>();
        for (JsonObject o : records) {
            String id = idOf(o);
            if (id != null) {
                used.add(id);
            }
        }
        List<JsonObject> out = new ArrayList<>(records.size());
        String previous = null;
        for (JsonObject src : records) {
            String type = typeOf(src);
            if (type == null) {
                throw new IllegalArgumentException("记录没有类型: " + src);
            }
            String id = idOf(src);
            if (id == null) {
                id = freshId(used);
                used.add(id);
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            o.addProperty("id", id);
            o.add("parentId", previous == null ? JsonNull.INSTANCE : new JsonPrimitive(previous));
            if (src.has("ts")) {
                o.add("ts", src.get("ts"));
            }
            for (Map.Entry<String, JsonElement> e : src.entrySet()) {
                if (!RESERVED.contains(e.getKey())) {
                    o.add(e.getKey(), e.getValue());
                }
            }
            out.add(o);
            previous = id;
        }
        return out;
    }

    /** 八位十六进制,不和 {@code used} 里的重。 */
    public static String freshId(Set<String> used) {
        String id;
        do {
            id = String.format("%08x", ThreadLocalRandom.current().nextInt());
        } while (used.contains(id));
        return id;
    }

    private long fileTime() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private static JsonObject parse(String line) {
        try {
            JsonElement el = JsonParser.parseString(line);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String typeOf(JsonObject o) {
        JsonElement t = o.get("type");
        return t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isString() ? t.getAsString() : null;
    }

    private static String idOf(JsonObject o) {
        JsonElement t = o.get("id");
        return t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isString() && !t.getAsString().isBlank()
                ? t.getAsString() : null;
    }
}
