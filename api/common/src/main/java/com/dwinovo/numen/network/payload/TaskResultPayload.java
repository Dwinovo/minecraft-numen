package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.task.TaskResult;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server-to-client payload: result of a previously requested tool execution.
 * Server drains task outboxes each tick and ships completed results back to
 * the owning player; the player's {@code EntityAgentLoop} feeds them into
 * the LLM conversation as {@code role:tool} messages, then triggers the
 * next turn when all pending results are in.
 *
 * <h2>Pairing</h2>
 * {@link #toolCallId} matches the one in the originating {@link ExecuteToolPayload}
 * and, transitively, the LLM's tool_call.id — this is the field that
 * threads request→execution→reply through the network boundary.
 *
 * <h2>Result body</h2>
 * Pre-serialised JSON string ({@link com.dwinovo.numen.task.TaskResult#toJson}).
 * Server-side decisions about field shape live in {@code TaskResult}; the
 * network layer just shuttles bytes.
 *
 * <h2>Too big for one payload</h2>
 * A result's length is not the payload's to bound, so it is {@link Wire#text()}
 * and the whole payload is measured before it is sent. One that does not fit
 * becomes a failed result for the same call ({@link #shrunk}): the model learns
 * how big it was, what fits and how to ask for less, and the connection stays up.
 */
public record TaskResultPayload(UUID entityUuid,
                                 String toolCallId,
                                 String resultJson) implements CustomPacketPayload, Wire.Oversized<TaskResultPayload> {

    public static final Type<TaskResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "task_result"));

    public static final StreamCodec<ByteBuf, TaskResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, TaskResultPayload::entityUuid,
                    Wire.TO_CLIENT.text(), TaskResultPayload::toolCallId,
                    Wire.TO_CLIENT.text(), TaskResultPayload::resultJson,
                    TaskResultPayload::new);

    /** 装不下的结果换成同一次调用的一条失败:说清多大、上限多少、怎么要少一点。 */
    @Override
    public TaskResultPayload shrunk(Predicate<TaskResultPayload> fits, int bytes, int budget) {
        return new TaskResultPayload(entityUuid, toolCallId, TaskResult.fail(
                Wire.TO_CLIENT.tooBig("The result of this call", bytes) + ", so it was not delivered. Ask for less of "
                        + "it at a time: a narrower range, or one page of a list with --page.",
                Map.of("result_bytes", bytes, "limit_bytes", budget)).toJson());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on client main thread (network layer arranges that). */
    public static void handle(TaskResultPayload p) {
        Constants.LOG.debug("[numen-net] task_result entity={} tool_call_id={} → {}",
                p.entityUuid(), p.toolCallId(), truncate(p.resultJson(), 200));
        ServerToolTransport.deliver(p.toolCallId(), p.resultJson());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
