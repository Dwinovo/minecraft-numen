package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.StoreException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 服务端的对话记录:{@code <存档>/numen/conversations/<会话 uuid>.jsonl},一段对话一个文件,一行一条 JSON({@code Rows} 的行,没有文件头)。
 * 目录按内容叫,不认识载体(书、指令、界面都只是指着某个会话 id)。只往后追加,文件从不删改;写的人只有一个(坐着她座位的客户端),
 * 所以没有冲突合并,也不加锁。
 *
 * <p>读写都排在同一条写车道上({@link Home#io}):写按交来的顺序落盘,读排在它前面交的写之后,所以刚追加的行一定读得到。
 * 读不成一行的(不是 JSON 对象)跳过并记一行警告,留在文件里。
 *
 * <p>线程:任何线程。
 */
public final class TranscriptStore {

    private final Home home;

    /** @param home 存放会话文件的目录与它的写车道 */
    public TranscriptStore(Home home) {
        this.home = home;
    }

    private Path file(UUID conversation) {
        return home.file(conversation + ".jsonl");
    }

    /** 往会话后面追加这些行,按给的顺序。写失败记警告(写是后台的事,没有谁在原地等着接异常)。 */
    public void append(UUID conversation, List<JsonObject> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        rows.forEach(row -> lines.append(row).append('\n'));
        Path file = file(conversation);
        home.io().write(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new StoreException("cannot append to the conversation " + file.getFileName() + ": " + e, e);
            }
        });
    }

    /** 读整份会话(物理顺序)交给 {@code job};在它前面已经交的追加都落盘之后才读。没有这份文件是空表。 */
    public <T> CompletableFuture<T> read(UUID conversation, Function<List<JsonObject>, T> job) {
        return CompletableFuture.supplyAsync(() -> job.apply(load(file(conversation))), home.io().executor());
    }

    private static List<JsonObject> load(Path file) {
        List<JsonObject> rows = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return rows;
        }
        int skipped = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    if (JsonParser.parseString(line) instanceof JsonObject row) {
                        rows.add(row);
                    } else {
                        skipped++;
                    }
                } catch (JsonParseException unreadable) {
                    skipped++;
                }
            }
        } catch (IOException e) {
            throw new StoreException("cannot read the conversation " + file.getFileName() + ": " + e, e);
        }
        if (skipped > 0) {
            Constants.LOG.warn("[numen-convo] skipped {} unreadable line(s) in {}", skipped, file.getFileName());
        }
        return rows;
    }
}
