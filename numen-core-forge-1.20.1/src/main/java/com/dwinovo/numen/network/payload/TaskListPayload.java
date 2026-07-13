package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.data.ClientTaskList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Revisioned server task snapshot, including progress and blocking telemetry. */
public record TaskListPayload(UUID uuid, long revision, boolean queuePaused,
                              boolean inventoryLocked, List<Entry> tasks) {
    public record Entry(String toolCallId, String toolName, String description,
                        String state, boolean active, boolean paused,
                        int progressCurrent, int progressTotal, String phase,
                        String blocker, int etaSeconds) { }

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "task_list");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
        buf.writeVarLong(revision);
        buf.writeBoolean(queuePaused);
        buf.writeBoolean(inventoryLocked);
        buf.writeVarInt(tasks.size());
        for (Entry entry : tasks) {
            buf.writeUtf(entry.toolCallId(), 256);
            buf.writeUtf(entry.toolName(), 128);
            buf.writeUtf(entry.description(), 512);
            buf.writeUtf(entry.state(), 32);
            buf.writeBoolean(entry.active());
            buf.writeBoolean(entry.paused());
            buf.writeVarInt(Math.max(0, entry.progressCurrent()));
            buf.writeVarInt(entry.progressTotal() < 0 ? 0 : entry.progressTotal() + 1);
            buf.writeUtf(entry.phase(), 64);
            buf.writeUtf(entry.blocker(), 512);
            buf.writeVarInt(entry.etaSeconds() < 0 ? 0 : entry.etaSeconds() + 1);
        }
    }

    public static TaskListPayload decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        long revision = buf.readVarLong();
        boolean paused = buf.readBoolean();
        boolean inventoryLocked = buf.readBoolean();
        int count = Math.min(256, buf.readVarInt());
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readUtf(256), buf.readUtf(128), buf.readUtf(512),
                    buf.readUtf(32), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(),
                    buf.readVarInt() - 1, buf.readUtf(64), buf.readUtf(512), buf.readVarInt() - 1));
        }
        return new TaskListPayload(id, revision, paused, inventoryLocked, entries);
    }

    public static void handle(TaskListPayload payload) {
        ClientTaskList.put(payload.uuid(), new ClientTaskList.Snapshot(payload.revision(),
                payload.queuePaused(), payload.inventoryLocked(), payload.tasks().stream()
                .map(entry -> new ClientTaskList.Entry(entry.toolCallId(), entry.toolName(), entry.description(),
                        entry.state(), entry.active(), entry.paused(), entry.progressCurrent(),
                        entry.progressTotal(), entry.phase(), entry.blocker(), entry.etaSeconds()))
                .toList(), System.currentTimeMillis()));
    }
}
