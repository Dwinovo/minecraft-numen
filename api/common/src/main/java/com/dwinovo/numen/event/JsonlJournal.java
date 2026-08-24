package com.dwinovo.numen.event;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EventQueue} 的落盘实现:一行一条 JSON,整本快照重写。
 *
 * <p>作用只有一个——关游戏时没来得及消费的输入,下次登录还在,同伴不失忆
 * ("主人你不在的时候我把矿挖完了")。文件很小(几条输入),整本重写比增量日志
 * 省一套 append/compact 簿记。
 *
 * <p>容错取向:坏行跳过、读不了当空箱、写失败只记日志不打断对话——**输入队列
 * 出问题不该让对话停摆**。
 *
 * <p>纯 JVM(Gson + nio),不碰 Minecraft:路径由调用方解析后注入。
 */
public final class JsonlJournal implements EventQueue.Journal {

    private final Path file;

    private JsonlJournal(Path file) {
        this.file = file;
    }

    public static JsonlJournal atFile(Path file) {
        return new JsonlJournal(file);
    }

    @Override
    public List<EventQueue.Entry> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<EventQueue.Entry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    out.add(new EventQueue.Entry(
                            o.get("type").getAsString(),
                            o.get("text").getAsString(),
                            o.has("ts") ? o.get("ts").getAsLong() : 0L,
                            o.has("urgent") && o.get("urgent").getAsBoolean()));
                } catch (RuntimeException bad) {
                    Constants.LOG.warn("[numen-queue] 跳过坏行 {}", file.getFileName());
                }
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-queue] 读不了 {}: {}", file.getFileName(), ex.getMessage());
        }
        return out;
    }

    @Override
    public void save(List<EventQueue.Entry> entries) {
        try {
            if (entries.isEmpty()) {
                Files.deleteIfExists(file);   // 空箱不留空文件
                return;
            }
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (EventQueue.Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("type", e.type());
                o.addProperty("text", e.text());
                o.addProperty("ts", e.ts());
                if (e.urgent()) {
                    o.addProperty("urgent", true);
                }
                sb.append(o).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-queue] 写不了 {}: {}", file.getFileName(), ex.getMessage());
        }
    }
}
