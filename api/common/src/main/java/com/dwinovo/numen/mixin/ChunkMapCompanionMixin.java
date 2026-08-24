package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Take companion fake players OUT of the client chunk-tracking / loading machinery.
 *
 * <p>A companion {@link NumenPlayer} is a real {@link ServerPlayer} subclass, so vanilla treats it
 * as a viewing client: it computes and sends per-chunk load/unload packets to it every move
 * ({@code updateChunkTracking}) and, via {@code updatePlayerStatus} → {@code DistanceManager.addPlayer},
 * keeps a full simulation-distance sphere of chunks loaded around it. For a fake player with no real
 * client both are wrong — the packets go nowhere, and the sphere makes every companion a heavyweight,
 * uncontrolled, moving chunk-loader (the source of the server-scale cost and the chunk-save freeze).
 *
 * <p>This mixin makes {@code ChunkMap} treat a companion as a non-viewing player:
 * <ul>
 *   <li>{@code skipPlayer → true}: stops the wasted client tracking AND the player loading ticket.
 *       The loading is added back, but bounded and self-cleaning, by
 *       {@link com.dwinovo.numen.entity.CompanionChunkLoader}.</li>
 *   <li>{@code updateChunkTracking → cancel}: skips the per-chunk load/unload packet sends that
 *       would otherwise run on every move (and on join, from {@code updatePlayerStatus}) for a
 *       client that isn't there. This is 1.20.1's per-chunk tracking path; the consolidated
 *       tracking-view object only exists in later versions.</li>
 * </ul>
 * The companion still ticks (players tick from the player list regardless) and is still tracked as an
 * entity, so real players nearby continue to see it.
 *
 * <p>Behavioural note: because a companion no longer counts as a player in {@code DistanceManager}, its
 * loading-pad chunks are not "near a player" for {@code NaturalSpawner} — hostile mobs won't naturally
 * spawn around a companion working far from any real player. This is consistent with the bounded-pad
 * intent (a companion is a lightweight loader, not a full player); tasks that rely on mobs spawning near
 * a remote companion (e.g. combat/farming away from the owner) will behave differently.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapCompanionMixin {

    @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
    private void numen$skipCompanionAsViewer(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof NumenPlayer) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void numen$suppressCompanionChunkPackets(ServerPlayer player, ChunkPos pos,
                                                     MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
                                                     boolean wasLoaded, boolean load, CallbackInfo ci) {
        if (player instanceof NumenPlayer) {
            ci.cancel();
        }
    }
}
