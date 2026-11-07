package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这一代旧版(0.1.4.x)的服务端出箱存在世界的 {@code data/numen/event_outbox.dat}(SavedData 的 id 是 {@code numen:event_outbox}):
 * 开服时迁移找的就是这个文件,里面攒的事件进了收件箱,文件改名留底。
 */
class LegacyOutboxFileTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path world;

    @Test
    void theOutboxSavedByThisGenerationIsFoundAndReplayed() throws IOException {
        Path data = world.resolve("data");
        Path saved = data.resolve("numen").resolve("event_outbox.dat");
        Files.createDirectories(saved.getParent());
        NbtIo.writeCompressed(oldOutboxSave(), saved);

        Path found = ServerBrains.legacyOutbox(data);
        assertEquals(saved, found, "迁移找的是这一代 SavedData 的落点");

        List<EventQueue.Entry> taken = LegacyMigration.outbox(found).get(A);
        assertEquals(List.of("task_finished", "body_log"), taken.stream().map(EventQueue.Entry::type).toList(), "攒下的事件按先后读回");
        assertFalse(Files.exists(saved), "读过的文件改了名,只做一次");
        assertTrue(Files.exists(saved.resolveSibling("event_outbox.dat.migrated")));
    }

    /** 0.1.4.x 的出箱存档:{@code data → outboxes → 同伴 → 条目},手搭,不经今天的写入代码。 */
    private static CompoundTag oldOutboxSave() {
        ListTag entries = new ListTag();
        entries.add(entry("body_log", "nearly drowned", 1_700_000_000_005L));
        entries.add(entry("task_finished", "矿挖完了", 1_700_000_000_000L));
        CompoundTag boxes = new CompoundTag();
        boxes.put(A.toString(), entries);
        CompoundTag data = new CompoundTag();
        data.put("outboxes", boxes);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", 4790);
        return root;
    }

    private static CompoundTag entry(String type, String text, long ts) {
        CompoundTag e = new CompoundTag();
        e.putString("type", type);
        e.putString("text", text);
        e.putLong("ts", ts);
        e.putBoolean("urgent", false);
        return e;
    }
}
