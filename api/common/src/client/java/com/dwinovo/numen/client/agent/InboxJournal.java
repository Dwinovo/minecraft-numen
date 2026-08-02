package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 收件箱的落盘账本:未消费的输入(主人的话、世界事件)每次变动整本重写,
 * 消费(倒箱进对话)即清空。作用只有一个——关游戏时没来得及消费的输入,
 * 下次登录还在,同伴不失忆("主人你不在的时候我把矿挖完了")。
 *
 * <p>文件很小(几条输入),整本快照重写比增量日志省一套 append/compact
 * 簿记;容错取向:损坏的行跳过,读不了当空箱,写失败只记日志不打断对话。
 * 消费后的内容活在对话日志(ConvoLog)里,这里不承担对话持久化。
 */
final class InboxJournal {

    /** type: "prompt"(主人的话,已裹 &lt;query&gt;) 或 "event"(&lt;event&gt; XML)。 */
    record Entry(String type, String text, long ts) {}

    private static final Gson GSON = new Gson();
    private final Path file;

    private InboxJournal(Path file) {
        this.file = file;
    }

    static InboxJournal forEntity(Path dir, UUID entityUuid) {
        return new InboxJournal(dir.resolve(entityUuid + ".inbox.jsonl"));
    }

    /** 当前未消费输入的完整快照写盘;空箱直接删文件。 */
    void save(List<Entry> entries) {
        try {
            if (entries.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("type", e.type());
                o.addProperty("text", e.text());
                o.addProperty("ts", e.ts());
                sb.append(GSON.toJson(o)).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-inbox] save failed ({}): {}", file.getFileName(), ex.getMessage());
        }
    }

    /** 上次会话遗留的未消费输入;没有或读不了 → 空列表。 */
    List<Entry> load() {
        if (!Files.exists(file)) return List.of();
        List<Entry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    out.add(new Entry(o.get("type").getAsString(),
                            o.get("text").getAsString(),
                            o.has("ts") ? o.get("ts").getAsLong() : 0L));
                } catch (RuntimeException bad) {
                    Constants.LOG.warn("[numen-inbox] skipping corrupt line in {}", file.getFileName());
                }
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-inbox] load failed ({}): {}", file.getFileName(), ex.getMessage());
        }
        return out;
    }
}
