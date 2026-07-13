package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.BiConsumer;

/** Task-page refresh and single-task controls. Core installs the server handler. */
public record TaskUiRequestPayload(UUID uuid, Action action, String toolCallId) {
    public enum Action { REFRESH, PAUSE, RESUME, CANCEL }
    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "task_ui_request");
    private static BiConsumer<TaskUiRequestPayload, ServerPlayer> handler = (p, s) -> {};
    public static void installHandler(BiConsumer<TaskUiRequestPayload, ServerPlayer> value) {
        handler = value == null ? (p, s) -> {} : value;
    }
    public void encode(FriendlyByteBuf buf) { buf.writeUUID(uuid); buf.writeEnum(action); buf.writeUtf(toolCallId == null ? "" : toolCallId, 256); }
    public static TaskUiRequestPayload decode(FriendlyByteBuf buf) { return new TaskUiRequestPayload(buf.readUUID(), buf.readEnum(Action.class), buf.readUtf(256)); }
    public static void handle(TaskUiRequestPayload p, ServerPlayer sender) { handler.accept(p, sender); }
}
