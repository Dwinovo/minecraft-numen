package com.dwinovo.numen.core.net;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record ExecuteToolPayload(UUID entityUuid,
                                  String toolCallId,
                                  String toolName,
                                  String argumentsJson,
                                  String reservationsJson,
                                  boolean reconnect) {

    public static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    public static final int MAX_TOOL_NAME_LENGTH = 128;
    public static final int MAX_ARGUMENTS_JSON_LENGTH = 16 * 1024;
    public static final int MAX_RESERVATIONS_JSON_LENGTH = 8 * 1024;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "execute_tool");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        buf.writeUtf(toolCallId, MAX_TOOL_CALL_ID_LENGTH);
        buf.writeUtf(toolName, MAX_TOOL_NAME_LENGTH);
        buf.writeUtf(argumentsJson, MAX_ARGUMENTS_JSON_LENGTH);
        buf.writeUtf(reservationsJson, MAX_RESERVATIONS_JSON_LENGTH);
        buf.writeBoolean(reconnect);
    }

    public static ExecuteToolPayload decode(FriendlyByteBuf buf) {
        return new ExecuteToolPayload(
                buf.readUUID(),
                buf.readUtf(MAX_TOOL_CALL_ID_LENGTH),
                buf.readUtf(MAX_TOOL_NAME_LENGTH),
                buf.readUtf(MAX_ARGUMENTS_JSON_LENGTH),
                buf.readUtf(MAX_RESERVATIONS_JSON_LENGTH),
                buf.readBoolean());
    }

    public static void handle(ExecuteToolPayload p, ServerPlayer player) {
        String who = player.getName().getString();
        Constants.LOG.debug("[numen-net] execute_tool from {} entity={} tool={} id={} args_chars={}",
                who, p.entityUuid(), p.toolName(), p.toolCallId(), p.argumentsJson().length());
        var server = player.level.getServer();
        com.dwinovo.numen.entity.NumenPlayer companion =
                com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, p.entityUuid());
        if (companion == null) {
            companion = com.dwinovo.numen.entity.Companions.respawn(server, p.entityUuid());
        }
        if (companion != null) {
            handleCompanion(p, player, companion);
            return;
        }
        replyError(player, p, "companion not found (never summoned, or its data is gone)");
    }

    private static void handleCompanion(ExecuteToolPayload p, ServerPlayer player,
                                        com.dwinovo.numen.entity.NumenPlayer companion) {
        if (!companion.isOwnedByPlayer(player.getUUID())) {
            replyError(player, p, "not the owner");
            return;
        }
        NumenTool tool = ToolRegistry.get(p.toolName());
        if (tool == null) {
            replyError(player, p, "unknown tool: " + p.toolName());
            return;
        }
        JsonObject args;
        try {
            args = JsonParser.parseString(p.argumentsJson()).getAsJsonObject();
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments JSON: " + ex.getMessage());
            return;
        }
        java.util.function.Consumer<String> reply = json ->
                com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(player,
                        TaskResultPayload.ID, new TaskResultPayload(p.entityUuid(), p.toolCallId(),
                                com.dwinovo.numen.core.task.TaskEvidence.decorateImmediate(companion, p.toolName(), json)));
        try {
            if (com.dwinovo.numen.core.task.CompanionTickDispatcher.handleDispatch(
                    player.level.getServer(), p.entityUuid(), p.toolCallId(), p.toolName(),
                    p.argumentsJson(), p.reconnect(), reply)) {
                return;
            }
            if (tool instanceof com.dwinovo.numen.core.tool.ServerNumenTool st) {
                JsonObject reservationFailure = com.dwinovo.numen.core.task.ReservationGuard.validate(
                        p.toolName(), args, p.reservationsJson(), companion);
                if (reservationFailure != null) {
                    reply.accept(reservationFailure.toString());
                    return;
                }
                try {
                    args.add("_numen_reservations", JsonParser.parseString(p.reservationsJson()));
                } catch (RuntimeException ignored) {
                    args.add("_numen_reservations", new com.google.gson.JsonArray());
                }
                st.runOnServer(p.toolCallId(), args, companion, reply);
                com.dwinovo.numen.core.task.CompanionTickDispatcher.attachArguments(
                        p.entityUuid(), p.toolCallId(), p.argumentsJson());
            } else {
                replyError(player, p, "tool not server-runnable: " + p.toolName());
            }
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments: " + ex.getMessage());
        }
    }

    public static void replyError(ServerPlayer player, ExecuteToolPayload p, String message) {
        Constants.LOG.warn("[numen-net] execute_tool rejected from {}: tool={} id={} reason={}",
                player.getName().getString(), p.toolName(), p.toolCallId(), message);
        String json = TaskResult.fail(message).toJson();
        com.dwinovo.numen.platform.Services.NETWORK.sendToPlayer(player,
                TaskResultPayload.ID, new TaskResultPayload(p.entityUuid(), p.toolCallId(), json));
    }
}
