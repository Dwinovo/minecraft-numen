package com.dwinovo.numen.entity;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.inbox.JsonlJournal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存档里已经躺着的旧条目:服务端出箱与客户端收件箱都存过类型叫 {@code event} 的条目,文本是当时拼好的
 * {@code <event kind="…">}。
 *
 * <p>这些条目不改写,原样读回来。表里已经没有 {@code event} 这一行,它们按没登记的类型处理——那一行
 * 与删掉的 {@code event} 行逐格相同(插话、原文给模型、打断不清、不是主人说的、不进聊天流、不恒急),
 * 而急不急当初入队时就记在条目上了。所以它们读回来之后的每一步都和从前一样。
 */
class LegacyEventEntriesTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path dir;

    /** 升级前的出箱存档:手搭,不经今天的写入代码。 */
    private static CompoundTag oldOutboxSave() {
        ListTag entries = new ListTag();
        entries.add(oldEntry("<event kind=\"task_finished\" day=\"3\" t=\"12:00\" id=\"t1\">矿挖完了</event>", T0, true));
        entries.add(oldEntry("<event kind=\"body_log\" day=\"3\" t=\"12:01\">nearly drowned</event>", T0 + 5, false));
        CompoundTag boxes = new CompoundTag();
        boxes.put(A.toString(), entries);
        CompoundTag root = new CompoundTag();
        root.put("outboxes", boxes);
        return root;
    }

    private static CompoundTag oldEntry(String text, long ts, boolean urgent) {
        CompoundTag e = new CompoundTag();
        e.putString("type", "event");
        e.putString("text", text);
        e.putLong("ts", ts);
        e.putBoolean("urgent", urgent);
        return e;
    }

    @Test
    void anOldOutboxIsReplayedAsTheWorldFactsItHeld() {
        EventOutbox box = EventOutbox.load(oldOutboxSave(), null);

        List<EventQueue.Entry> taken = box.take(A, T0 + 10);

        assertEquals(List.of("event", "event"), taken.stream().map(EventQueue.Entry::type).toList(),
                "条目原样读回,不改写");
        assertTrue(taken.get(0).urgent(), "当初是急件,读回来还是急件");
        assertFalse(taken.get(1).urgent(), "当初不急,读回来也不会变急");
        assertEquals(List.of("<events>\n"
                        + "<event kind=\"task_finished\" day=\"3\" t=\"12:00\" id=\"t1\">矿挖完了</event>\n"
                        + "<event kind=\"body_log\" day=\"3\" t=\"12:01\">nearly drowned</event>\n</events>"),
                EventQueue.render(taken, T0 + 10), "和从前一样按时间排进 <events>,文本一字不改");
    }

    @Test
    void anOldClientInboxBehavesAsItDidBeforeTheUpgrade() throws IOException {
        Path file = dir.resolve("inbox.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"event\",\"text\":\"<event kind=\\\"death\\\" day=\\\"0\\\" t=\\\"06:43\\\">你刚才死了</event>\",\"ts\":"
                        + T0 + ",\"urgent\":true}",
                "{\"type\":\"query\",\"text\":\"<query>回来</query>\",\"ts\":" + (T0 + 1) + ",\"urgent\":true}",
                "{\"type\":\"event\",\"text\":\"<event kind=\\\"body_log\\\">吃了个面包</event>\",\"ts\":" + (T0 + 2) + "}")
                + "\n", StandardCharsets.UTF_8);

        EventQueue q = new EventQueue(JsonlJournal.atFile(file));

        assertEquals(3, q.size());
        assertTrue(q.hasUrgent());
        assertEquals(1, q.clearInterrupted(), "按停止只清主人的话,旧事实留着");
        assertTrue(q.chatPreview().isEmpty(), "旧事实不进聊天流");
        for (EventQueue.Entry e : q.entries()) {
            assertEquals(EventTypes.Delivery.STEER, EventTypes.get(e.type()).delivery(), "照旧当插话交给模型");
            assertFalse(EventTypes.get(e.type()).fromOwner(), "不会被当成主人说的话");
        }
        assertEquals(List.of("<events>\n<event kind=\"death\" day=\"0\" t=\"06:43\">你刚才死了</event>\n"
                        + "<event kind=\"body_log\">吃了个面包</event>\n</events>"),
                EventQueue.render(q.takeEntries(T0 + 3), T0 + 3));
    }
}
