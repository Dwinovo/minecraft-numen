package com.dwinovo.numen.core.debug;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.PathDebugPayload;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.search.Route;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 寻路调试状态发布:每 {@link #INTERVAL} tick 把每个在走的同伴的状态(还没走完的那几步、路上要挖与要放的格、朝着的那一格)
 * 打包成 {@link PathDebugPayload} 发给同维度开了调试的主人;客户端逐帧画成世界空间的线与方框。不产生任何粒子。
 */
public final class PathDebugRenderer {

    private static final int INTERVAL = 5;

    private static int tickCounter;

    private PathDebugRenderer() {}

    public static void serverTick(MinecraftServer server) {
        if (!PathDebug.anyEnabled()) {
            return;
        }
        if (++tickCounter % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer body : server.getPlayerList().getPlayers()) {
            if (!(body instanceof NumenPlayer companion)) {
                continue;
            }
            Trip trip = Trip.current(companion);
            if (trip == null) {
                continue;
            }
            ServerLevel level = companion.serverLevel();
            List<ServerPlayer> viewers = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (PathDebug.isEnabled(p.getUUID()) && p.level() == level) {
                    viewers.add(p);
                }
            }
            if (viewers.isEmpty()) {
                continue;
            }
            PathDebugPayload payload = snapshot(companion, trip);
            for (ServerPlayer viewer : viewers) {
                NumenNetwork.sendToPlayer(viewer, payload);
            }
        }
    }

    /** 采集一趟路此刻的可视状态:还没走完的那几步与它们要动的格,朝着的那一格画成目标框。 */
    private static PathDebugPayload snapshot(NumenPlayer companion, Trip trip) {
        List<Long> path = new ArrayList<>();
        List<Long> toBreak = new ArrayList<>();
        List<Long> toPlace = new ArrayList<>();
        List<Route.Leg> legs = trip.remaining();
        if (!legs.isEmpty()) {
            path.add(legs.get(0).maneuver().from().asLong());
        }
        for (Route.Leg leg : legs) {
            path.add(leg.maneuver().to().asLong());
            for (Edit edit : leg.maneuver().edits()) {
                switch (edit) {
                    case Edit.Dig dig -> toBreak.add(dig.pos().asLong());
                    case Edit.Place place -> toPlace.add(place.pos().asLong());
                    default -> { }
                }
            }
        }
        return new PathDebugPayload(companion.getUUID(), path, List.of(), List.of(), toBreak, toPlace, List.of(),
                List.of(trip.toward().asLong()), List.of());
    }
}
