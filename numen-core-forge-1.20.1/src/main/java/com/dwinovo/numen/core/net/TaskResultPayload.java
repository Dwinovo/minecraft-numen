package com.dwinovo.numen.core.net;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.core.tool.CoreServerTools;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record TaskResultPayload(UUID entityUuid,
                                 String toolCallId,
                                 String resultJson) {

    public static final int MAX_TOOL_CALL_ID_LENGTH = 128;
    public static final int MAX_RESULT_JSON_LENGTH = 16 * 1024;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "task_result");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        buf.writeUtf(toolCallId, MAX_TOOL_CALL_ID_LENGTH);
        buf.writeUtf(resultJson, MAX_RESULT_JSON_LENGTH);
    }

    public static TaskResultPayload decode(FriendlyByteBuf buf) {
        return new TaskResultPayload(
                buf.readUUID(),
                buf.readUtf(MAX_TOOL_CALL_ID_LENGTH),
                buf.readUtf(MAX_RESULT_JSON_LENGTH));
    }

    public static void handle(TaskResultPayload p) {
        Constants.LOG.debug("[numen-net] task_result entity={} tool_call_id={}",
                p.entityUuid(), p.toolCallId());
        CoreServerTools.deliver(p.entityUuid(), p.toolCallId(), p.resultJson());
    }
}
