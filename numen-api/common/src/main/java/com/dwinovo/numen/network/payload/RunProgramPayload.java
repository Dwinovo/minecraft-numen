package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.OwnedBody;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.CallObserver;
import com.dwinovo.numen.program.NetworkTransport;
import com.dwinovo.numen.program.ModuleSet;
import com.dwinovo.numen.program.RunResult;
import com.dwinovo.numen.program.ServerPrograms;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "run this program on my companion". The whole program goes up once, with her module list
 * (name → content fingerprint) and the module texts this connection has not sent yet ({@link ModuleSet}); the server
 * runs it beside the body and the data and answers with one {@link ProgramResultPayload}. Whatever the program calls
 * that only the owner's client can answer comes back as a {@link ClientCallPayload}.
 *
 * <h2>Trust model</h2>
 * The program is the owner's own code, run with the companion's own abilities, so the checks are the ones every
 * body-bound packet gets ({@link OwnedBody}): the target must be the sender's companion. Distance grants nothing.
 * What the program may do to the world is decided by the permission layer at the moment it does it, not here.
 *
 * <h2>Wire format</h2>
 * Every string is {@link Wire#text()}: the program and the module texts are the model's and the owner's, not ours to
 * bound. The payload is {@link Wire.Fragmentable}: past one upward packet it goes as fragments and the server puts it
 * back together before {@link #handle} sees it. The whole payload is measured against {@link Wire#MESSAGE_BYTES}
 * before it leaves the client, and a program that does not fit even that is answered there ({@link #tooBigWords})
 * instead of being sent.
 *
 * @param programId the program's id; the n-th call inside it is {@code <programId>#<n>}
 */
public record RunProgramPayload(UUID entityUuid, String programId, String code, ModuleSet modules)
        implements CustomPacketPayload, Wire.Fragmentable {

    public static final Type<RunProgramPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "run_program"));

    public static final StreamCodec<ByteBuf, RunProgramPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RunProgramPayload::entityUuid,
                    Wire.TO_SERVER.text(), RunProgramPayload::programId,
                    Wire.TO_SERVER.text(), RunProgramPayload::code,
                    ModuleSet.STREAM_CODEC, RunProgramPayload::modules,
                    RunProgramPayload::new);

    /** 这段程序连同要带的模块编码后的字节数。 */
    public int size() {
        return Wire.size(STREAM_CODEC, this, Unpooled::buffer);
    }

    /** 这段程序连整条消息的上限都装不下:不送,就地给模型的话。说清多大、上限多少、怎么办。 */
    public static String tooBigWords(int bytes) {
        return Wire.TO_SERVER.tooBigMessage("This program with the modules it needs", bytes) + ", so it was not sent. Make "
                + "the program shorter: a long list or grid goes in a module you save once with numen.module.save and "
                + "then call by name.";
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(RunProgramPayload p, ServerPlayer player) {
        Constants.LOG.debug("[numen-net] ← run_program from {} entity={} id={} code_chars={} bodies={}",
                player.getName().getString(), p.entityUuid(), p.programId(), p.code().length(),
                p.modules().bodies().size());
        NumenPlayer companion = OwnedBody.of(player, p.entityUuid(), message -> {
            Constants.LOG.warn("[numen-net] ✗ run_program rejected from {}: id={} reason={}",
                    player.getName().getString(), p.programId(), message);
            reply(player, p, RunResult.refused(message));
        });
        if (companion == null) {
            return;
        }
        ServerPrograms.run(companion, p.entityUuid(), player.getUUID(),
                new ServerPrograms.Request(p.programId(), p.code(), p.modules(), true), NetworkTransport.INSTANCE,
                CallObserver.NONE, result -> reply(player, p, result));
    }

    private static void reply(ServerPlayer player, RunProgramPayload p, RunResult result) {
        NumenNetwork.sendToPlayer(player, new ProgramResultPayload(p.entityUuid(), p.programId(), result.toJson()));
    }
}
