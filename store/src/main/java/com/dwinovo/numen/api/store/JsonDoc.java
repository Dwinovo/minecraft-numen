package com.dwinovo.numen.api.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 小文档:一个 JSON 对象一个文件,带版本号,读时迁移,写时先写临时文件再原子替换({@link Atomic})。
 *
 * <p>版本号是顶层的数字 {@value #VERSION};没有它的文件是第 1 版(版本号出现之前写的)。
 * <b>文档内容里看不到这个字段</b>:读时被摘掉、写时再盖上,所以上层的记录格式不必为它留位置,
 * 也不会和自己的键撞名。读到旧版本就在内存里一步步升到当前版本,磁盘上的文件等下一次写才换成新版;
 * 读到更新的版本(回退了模组)不硬读,抛 {@link StoreException}。
 *
 * <p>文档是整份读、整份写;写出去的文本在调用线程上就定下来,真正落盘交给 {@link Io}。
 */
public final class JsonDoc {

    /** 版本号在文件顶层的键。 */
    public static final String VERSION = "v";

    /** 把第 n 版的文档升成第 n+1 版。拿到的文档里没有 {@link #VERSION}。 */
    @FunctionalInterface
    public interface Upgrade {
        JsonObject apply(JsonObject doc);
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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Format format;
    private final Io io;

    public JsonDoc(Path file, Format format, Io io) {
        format.check();
        this.file = file;
        this.format = format;
        this.io = io;
    }

    public Path file() {
        return file;
    }

    /**
     * 读;文件不在是空。读不了、不是 JSON 对象、版本比当前新、升级出错,都抛 {@link StoreException}。
     * 返回的是升到当前版本之后的内容。
     */
    public Optional<JsonObject> read() {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException e) {
            throw new StoreException("读不了 " + file + ": " + e, e);
        }
        JsonObject doc;
        int version;
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                throw new StoreException(file + " 不是 JSON 对象");
            }
            doc = root.getAsJsonObject();
            // 版本号是数字;不是数字的 v 是第 1 版文档自己的内容(比如一张"名字 → 内容"的表里叫 v 的那项)
            version = doc.get(VERSION) instanceof com.google.gson.JsonPrimitive p && p.isNumber()
                    ? doc.remove(VERSION).getAsInt() : 1;
        } catch (RuntimeException e) {
            if (e instanceof StoreException se) {
                throw se;
            }
            throw new StoreException("读不懂 " + file + ": " + e, e);
        }
        if (version > format.current) {
            throw new StoreException(file + " 是第 " + version + " 版,本程序只认到第 " + format.current + " 版");
        }
        try {
            for (int v = version; v < format.current; v++) {
                doc = format.steps.get(v).apply(doc);
            }
        } catch (RuntimeException e) {
            throw new StoreException("升级 " + file + " 失败(从第 " + version + " 版): " + e, e);
        }
        return Optional.of(doc);
    }

    /** 交给执行器去读。 */
    public CompletableFuture<Optional<JsonObject>> readAsync() {
        return io.read(this::read);
    }

    /** 整份写;失败交给 {@link Io#onError()}。传进来的对象不被改动。 */
    public void write(JsonObject doc) {
        JsonObject stamped = new JsonObject();
        stamped.addProperty(VERSION, format.current);
        doc.entrySet().forEach(e -> {
            if (!VERSION.equals(e.getKey())) {
                stamped.add(e.getKey(), e.getValue());
            }
        });
        String text = GSON.toJson(stamped);
        io.write(() -> Atomic.write(file, text));
    }

    /** 删掉文件;没有也不算错。和写同一个顺序。 */
    public void delete() {
        io.write(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new StoreException("删不了 " + file + ": " + e, e);
            }
        });
    }
}
