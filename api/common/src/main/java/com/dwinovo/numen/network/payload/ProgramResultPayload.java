package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.ProgramUplink;
import com.dwinovo.numen.program.RunResult;
import net.minecraft.network.FriendlyByteBuf;
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
        implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "program_result");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        Wire.writeText(buf, programId);
        Wire.writeText(buf, resultJson);
    }

    public static ProgramResultPayload read(FriendlyByteBuf buf) {
        return new ProgramResultPayload(buf.readUUID(), Wire.readText(buf), Wire.readText(buf));
    }

    /** Client-side handler. */
    public static void handle(ProgramResultPayload p) {
        Constants.LOG.debug("[numen-net] program_result entity={} id={} chars={}", p.entityUuid(), p.programId(),
                p.resultJson().length());
        ProgramUplink.CONNECTION.deliver(p.programId(), RunResult.fromJson(p.resultJson()));
    }
}
