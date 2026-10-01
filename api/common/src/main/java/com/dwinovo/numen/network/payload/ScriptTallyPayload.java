package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.script.Scripts;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client → Server:她的大脑跑完了一份有名字的脚本(跑完、出错或被停下),记进这份脚本的战绩。脚本在大脑那一侧跑,战绩跟着
 * 脚本存在服务端主人名下,所以由大脑送过来。只认主人发来的。
 *
 * @param why 没跑完的原因;跑完是空串
 */
public record ScriptTallyPayload(UUID entityUuid, String script, boolean ok, int line, String why)
        implements CustomPacketPayload {

    /** 原因最多送多长:清单里只用得上开头,{@code script show} 里也不该是一大段。 */
    private static final int WHY_MAX = 300;

    public static final Type<ScriptTallyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "script_tally"));

    public static final StreamCodec<ByteBuf, ScriptTallyPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ScriptTallyPayload::entityUuid,
                    ByteBufCodecs.STRING_UTF8, ScriptTallyPayload::script,
                    ByteBufCodecs.BOOL, ScriptTallyPayload::ok,
                    ByteBufCodecs.VAR_INT, ScriptTallyPayload::line,
                    ByteBufCodecs.STRING_UTF8, ScriptTallyPayload::why,
                    ScriptTallyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 大脑那一侧:把一次运行的结局送上去,和工具调用同一个上行出口。 */
    public static void send(UUID companion, String script, ScriptCall.Tally tally) {
        String why = tally.error() == null ? "" : tally.error();
        ServerToolTransport.uplink.accept(new ScriptTallyPayload(companion, script, tally.ok(), tally.line(),
                why.length() <= WHY_MAX ? why : why.substring(0, WHY_MAX) + "..."));
    }

    /** Server main thread。 */
    public static void handle(ScriptTallyPayload p, ServerPlayer sender) {
        NumenPlayer companion = NumenPlayer.findByUuid(sender.level().getServer(), p.entityUuid());
        if (companion == null || !companion.isOwnedByPlayer(sender.getUUID())) {
            Constants.LOG.debug("[numen-net] script_tally for {} ignored: not a companion of {}", p.entityUuid(),
                    sender.getName().getString());
            return;
        }
        Scripts.tally(companion, p.script(), p.ok(), p.line(), p.why().isEmpty() ? null : p.why());
    }
}
