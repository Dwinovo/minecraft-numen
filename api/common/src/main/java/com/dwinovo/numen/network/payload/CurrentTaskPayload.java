package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Server → Client:她此刻在做什么。<b>这是「她在做什么」的唯一真源</b>。
 *
 * <h2>为什么必须由服务端推</h2>
 * 活不都是客户端派起来的:服务器重启后的重放、死亡复活后的重放都不经过它。
 * 客户端要是靠"我派出去过什么"自己记账,这些活它一概不知道——她明明在跟随,
 * 头顶却没有「正在…」气泡,{@code <runtime_state>} 里也写着她闲着。
 * 槽在服务端,推送就从服务端来。
 *
 * <p>槽的转换只发生在 {@code CompanionBrain} 一处,所以推送也只从那一处发出:
 * 派发、重放、被顶替、干完、死亡清空,走的是同一个出口。客户端不再推断,只照抄。
 *
 * @param taskId    公开任务 id;<b>空串 = 她现在闲着</b>
 * @param tool      工具名(collect_items / mine / follow …)
 * @param describe  这件活的人话描述,由任务记录自己给出
 * @param standing  常驻(没有终点,不会发 task_finished)
 * @param elapsedMs 已经干了多久——重放回来的活也有正确的起点,不会从推送那一刻重新计时
 */
public record CurrentTaskPayload(UUID entityUuid, String taskId, String tool,
                                 String describe, boolean standing, long elapsedMs)
        implements CustomPacketPayload {

    public static final Type<CurrentTaskPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "current_task"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CurrentTaskPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, CurrentTaskPayload::entityUuid,
                    ByteBufCodecs.STRING_UTF8, CurrentTaskPayload::taskId,
                    ByteBufCodecs.STRING_UTF8, CurrentTaskPayload::tool,
                    ByteBufCodecs.STRING_UTF8, CurrentTaskPayload::describe,
                    ByteBufCodecs.BOOL, CurrentTaskPayload::standing,
                    ByteBufCodecs.VAR_LONG, CurrentTaskPayload::elapsedMs,
                    CurrentTaskPayload::new);

    /** 她闲着。 */
    public static CurrentTaskPayload idle(UUID entityUuid) {
        return new CurrentTaskPayload(entityUuid, "", "", "", false, 0L);
    }

    public boolean idle() {
        return taskId.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(CurrentTaskPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.currentTask.accept(p);
    }
}
