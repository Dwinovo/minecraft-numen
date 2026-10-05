package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.ProgramUplink;
import com.dwinovo.numen.program.RunResult;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server-to-client payload: the one answer to a {@link RunProgramPayload} — the program's receipt with the outcome of
 * every call and the modules it used, or the list of module texts the server still needs ({@link RunResult}).
 *
 * <h2>Bounded by construction</h2>
 * The receipt is read by the model, so the program bounds it where it writes it ({@code ScriptLimits}: each stderr
 * record and the whole stderr, the returned value, the printed text, each call's recorded text), and every call's
 * outcome is a short text. The payload is therefore far under {@link Wire#TO_CLIENT}; one that is not is a bug in
 * whatever filled it and {@link Wire#fit} throws.
 */
public record ProgramResultPayload(UUID entityUuid, String programId, String resultJson)
        implements CustomPacketPayload {

    public static final Type<ProgramResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "program_result"));

    public static final StreamCodec<ByteBuf, ProgramResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ProgramResultPayload::entityUuid,
                    Wire.TO_CLIENT.text(), ProgramResultPayload::programId,
                    Wire.TO_CLIENT.text(), ProgramResultPayload::resultJson,
                    ProgramResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. */
    public static void handle(ProgramResultPayload p) {
        Constants.LOG.debug("[numen-net] program_result entity={} id={} chars={}", p.entityUuid(), p.programId(),
                p.resultJson().length());
        ProgramUplink.CONNECTION.deliver(p.programId(), RunResult.fromJson(p.resultJson()));
    }
}
