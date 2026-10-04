package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.sdk.Dispatcher;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "a script running in the brain on my client called this API function — please execute it on
 * my companion". The function is named in full ({@code numen.work.dig}), the arguments are the script values the call was
 * read into ({@link Dispatcher#invocation}); the server reads them again with the same codecs before running the function
 * ({@link Dispatcher#serve}).
 *
 * <h2>Trust model</h2>
 * The server treats this as unvalidated input. Validation chain:
 * <ol>
 *   <li>Target must be an {@link com.dwinovo.numen.entity.NumenPlayer} (searched across ALL
 *       dimensions — a working companion may be in the Nether while the owner
 *       waits in the overworld).</li>
 *   <li>Sender must be the entity's owner (UUID comparison, cross-dimension safe).</li>
 *   <li>The action must be a registered server action, and its arguments must read with its parameter table.</li>
 * </ol>
 * There is deliberately NO owner-distance check: the whole point of the
 * chunk-ticket system is that the companion keeps working far away, and the
 * actions run at the <em>entity's</em> location with the entity's own abilities
 * — owner distance grants nothing exploitable. (Ownership is the auth.)
 *
 * <p>Any failure path emits an immediate
 * {@link TaskResultPayload} with {@code ok:false} back to the sender so
 * the script waiting on the call gets its result — silently dropping a call
 * would leave it waiting forever.
 *
 * <h2>Wire format</h2>
 * Every string is {@link Wire#text()}: none of them is ours to bound (the call id and the arguments come from the
 * model's script), so the whole payload is measured against {@link Wire#TO_SERVER} before it leaves the client, and a
 * call that does not fit is answered there ({@link #tooBig}) instead of being sent.
 */
public record ExecuteActionPayload(UUID entityUuid,
                                   String callId,
                                   String action,
                                   String argumentsJson) implements CustomPacketPayload {

    public static final Type<ExecuteActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "execute_action"));

    public static final StreamCodec<ByteBuf, ExecuteActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ExecuteActionPayload::entityUuid,
                    Wire.TO_SERVER.text(), ExecuteActionPayload::callId,
                    Wire.TO_SERVER.text(), ExecuteActionPayload::action,
                    Wire.TO_SERVER.text(), ExecuteActionPayload::argumentsJson,
                    ExecuteActionPayload::new);

    /**
     * 这次调用编码后是 {@code bytes} 字节,一个上行的包装不下:不送,就地回给脚本的那条失败。说清多大、上限多少、怎么办。
     */
    public static String tooBig(int bytes) {
        JsonObject data = new JsonObject();
        data.addProperty("call_bytes", bytes);
        data.addProperty("limit_bytes", Wire.TO_SERVER.bytes());
        return ApiReply.error(ErrorKind.FAILED, Wire.TO_SERVER.tooBig("This call", bytes) + ", so it was not sent. "
                + "Split the work into several shorter calls: a long grid or list goes in as several steps.", null,
                data).toString();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(ExecuteActionPayload p, ServerPlayer player) {
        String who = player.getName().getString();
        Constants.LOG.debug("[numen-net] ← execute_action from {} entity={} action={} id={} args_chars={}",
                who, p.entityUuid(), p.action(), p.callId(), p.argumentsJson().length());

        var server = player.level().getServer();
        com.dwinovo.numen.entity.NumenPlayer companion =
                com.dwinovo.numen.entity.NumenPlayer.findByUuid(server, p.entityUuid());
        if (companion == null) {
            // 她死着的时候不许从这儿把她拉起来:复活的唯一出口是定时复活
            // (Companions.tickRespawns,读注册表的 diedAt)。两条复活路各自不知道对方,
            // 结果是一次死亡复活出两具同 UUID 的身体同时在玩家列表里 —— 排程器会在
            // 两具之间无限重建大脑,每次还重放一遍她手上的活。
            var reg = com.dwinovo.numen.entity.CompanionRegistry.get(server).find(p.entityUuid());
            if (reg != null && reg.diedAt() > 0L) {
                replyError(player, p, "她刚死了,正在复活途中——等复活事件到了再派");
                return;
            }
            companion = com.dwinovo.numen.entity.Companions.respawn(server, p.entityUuid());
        }
        if (companion == null) {
            replyError(player, p, "companion not found (never summoned, or its data is gone)");
            return;
        }
        if (!companion.isOwnedByPlayer(player.getUUID())) {
            replyError(player, p, "not the owner");
            return;
        }
        JsonObject args;
        try {
            args = JsonParser.parseString(p.argumentsJson()).getAsJsonObject();
        } catch (RuntimeException ex) {
            replyError(player, p, "invalid arguments JSON: " + ex.getMessage());
            return;
        }
        // 当场的当场回;等的、占身体的,结果随等到的那一刻、活的受理回来
        Dispatcher.serve(p.action(), args, companion, p.callId(), json ->
                NumenNetwork.sendToPlayer(player, new TaskResultPayload(p.entityUuid(), p.callId(), json)));
    }

    /**
     * 被拒时<b>连参数一起打全</b>:调用成功时参数是截断记录的,一旦被拒,事后要判"脚本到底发了什么"就只剩一句错误原因。
     * 失败是稀有事件,把这一条打全不会淹没日志。
     */
    public static void replyError(ServerPlayer player, ExecuteActionPayload p, String message) {
        Constants.LOG.warn("[numen-net] ✗ execute_action rejected from {}: action={} id={} reason={} args={}",
                player.getName().getString(), p.action(), p.callId(), message, p.argumentsJson());
        String json = ApiReply.error(ErrorKind.FAILED, message, null, null).toString();
        NumenNetwork.sendToPlayer(player, new TaskResultPayload(p.entityUuid(), p.callId(), json));
    }
}
