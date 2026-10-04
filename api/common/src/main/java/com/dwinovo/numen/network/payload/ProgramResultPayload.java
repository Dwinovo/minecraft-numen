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
import java.util.function.Predicate;

/**
 * Server-to-client payload: the one answer to a {@link RunProgramPayload} — the program's receipt with the outcome of
 * every call and the modules it used, or the list of module texts the server still needs ({@link RunResult}).
 *
 * <h2>Too big for one payload</h2>
 * A receipt's length is not the payload's to bound, so it is {@link Wire#text()} and the whole payload is measured
 * before it is sent. One that does not fit becomes a failed receipt for the same program ({@link #shrunk}): the model
 * learns how big it was and how to ask for less, and the connection stays up.
 */
public record ProgramResultPayload(UUID entityUuid, String programId, String resultJson)
        implements CustomPacketPayload, Wire.Oversized<ProgramResultPayload> {

    public static final Type<ProgramResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "program_result"));

    public static final StreamCodec<ByteBuf, ProgramResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ProgramResultPayload::entityUuid,
                    Wire.TO_CLIENT.text(), ProgramResultPayload::programId,
                    Wire.TO_CLIENT.text(), ProgramResultPayload::resultJson,
                    ProgramResultPayload::new);

    /** 装不下的答复换成同一段程序的一张失败回执:说清多大、上限多少、怎么要少一点。 */
    @Override
    public ProgramResultPayload shrunk(Predicate<ProgramResultPayload> fits, int bytes, int budget) {
        RunResult failed = RunResult.refused(Wire.TO_CLIENT.tooBig("The receipt of this program", bytes)
                + ", so it was not delivered. Have the program return or print less: a narrower range or fewer things.");
        return new ProgramResultPayload(entityUuid, programId, failed.toJson());
    }

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
