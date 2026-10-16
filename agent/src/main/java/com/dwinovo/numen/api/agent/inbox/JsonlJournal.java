package com.dwinovo.numen.api.agent.inbox;

import com.dwinovo.numen.api.ai.AiLog;
import com.dwinovo.numen.api.store.AppendLog;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.StoreException;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EventQueue} 的落盘实现:一个 {@link AppendLog},一条输入一条记录({@code type} 就是输入的类型),
 * 每次存盘把整本现状换进去。本类只管输入条目的记录格式(类型、正文、时刻、急不急);头、版本、id、
 * 坏行与原子替换都是 {@link AppendLog} 的事。
 *
 * <p>作用只有一个——关游戏时没来得及消费的输入,下次登录还在,同伴不失忆
 * ("主人你不在的时候我把矿挖完了")。文件很小(几条输入),队列的变化又以"取走若干条"为主,
 * 所以每次整本重写,不记"取走了哪条"的簿记。第 1 版是没有头的一行一条,第 2 版起带头与 id。
 *
 * <p>容错取向:坏行跳过、读不了当空箱、写失败只记日志不打断对话——<b>输入队列
 * 出问题不该让对话停摆</b>。
 *
 * <p>纯 JVM,不碰 Minecraft:路径由调用方解析后注入。
 */
public final class JsonlJournal implements EventQueue.Journal {

    private final AppendLog log;

    private JsonlJournal(Path file, Io io) {
        this.log = new AppendLog(file, AppendLog.Format.of(2).upgrade(1, records -> records), io);
    }

    /** 写在调用线程上直接做。 */
    public static JsonlJournal atFile(Path file) {
        return atFile(file, Io.direct(e -> AiLog.LOG.warn("[numen-queue] {}", e.getMessage())));
    }

    /** 同上,写的线程由宿主给({@code io} 的执行器)。 */
    public static JsonlJournal atFile(Path file, Io io) {
        return new JsonlJournal(file, io);
    }

    @Override
    public List<EventQueue.Entry> load() {
        AppendLog.Loaded loaded;
        try {
            loaded = log.read();
        } catch (StoreException ex) {
            AiLog.LOG.warn("[numen-queue] {}", ex.getMessage());
            return List.of();
        }
        List<EventQueue.Entry> out = new ArrayList<>();
        int skipped = loaded.skipped();
        for (AppendLog.Record r : loaded.records()) {
            String text = r.string("text");
            if (text == null) {
                skipped++;
                continue;
            }
            out.add(new EventQueue.Entry(r.type(), text, r.ts(), r.json().has("urgent")
                    && r.json().get("urgent").getAsBoolean(), null, r.id()));
        }
        if (skipped > 0) {
            AiLog.LOG.warn("[numen-queue] 跳过 {} 条坏记录 {}", skipped, log.file().getFileName());
        }
        return out;
    }

    @Override
    public void save(List<EventQueue.Entry> entries) {
        List<JsonObject> records = new ArrayList<>(entries.size());
        for (EventQueue.Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("type", e.type());
            if (e.id() != null) {
                o.addProperty("id", e.id());   // 编号是条目的,不是这次重写数出来的:重启前后同一条还是它
            }
            o.addProperty("ts", e.ts());
            o.addProperty("text", e.text());
            if (e.urgent()) {
                o.addProperty("urgent", true);
            }
            records.add(o);
        }
        log.rewrite(records);   // 空箱就是删文件:不留空壳
    }
}
