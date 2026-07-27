package com.dwinovo.numen.fix;

import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.scaffold.TemporaryScaffoldController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Coordinates fake-player login with the real player's chunk handshake. */
public final class NumenRuntimeFixes {
    private static final int OWNER_STABLE_TICKS = 100;
    private static final Map<MinecraftServer, Set<UUID>> PENDING_RESTORES = new WeakHashMap<>();
    private static final Set<MinecraftServer> STOPPING =
        Collections.newSetFromMap(new WeakHashMap<>());

    private NumenRuntimeFixes() {
    }

    public static void prepareBeforeJoin(NumenPlayer player, UUID ownerUuid, Vec3 targetPosition) {
        if (player.getOwnerUuid() == null) {
            player.setOwnerUuid(ownerUuid);
        }
        if (targetPosition != null) {
            player.snapTo(
                targetPosition.x,
                targetPosition.y,
                targetPosition.z,
                player.getYRot(),
                player.getXRot()
            );
        }
    }

    public static synchronized void enqueueOwnerRestore(MinecraftServer server, UUID ownerUuid) {
        if (server == null || ownerUuid == null || STOPPING.contains(server)) {
            return;
        }
        PENDING_RESTORES
            .computeIfAbsent(server, ignored -> new LinkedHashSet<>())
            .add(ownerUuid);
    }

    public static boolean isOwnerReady(ServerPlayer owner) {
        return owner != null
            && !(owner instanceof NumenPlayer)
            && !owner.hasDisconnected()
            && owner.connection != null
            && (owner.connection.hasClientLoaded() || owner.tickCount >= OWNER_STABLE_TICKS)
            && owner.level().isLoaded(owner.blockPosition());
    }

    public static synchronized void tick(MinecraftServer server) {
        if (STOPPING.contains(server)) {
            return;
        }

        Set<UUID> pending = PENDING_RESTORES.get(server);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            UUID ownerUuid = iterator.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
            if (owner == null || owner.hasDisconnected()) {
                iterator.remove();
                continue;
            }
            if (!isOwnerReady(owner)) {
                continue;
            }

            iterator.remove();
            restoreLivingCompanions(server, owner);
        }

        if (pending.isEmpty()) {
            PENDING_RESTORES.remove(server);
        }
    }

    private static void restoreLivingCompanions(MinecraftServer server, ServerPlayer owner) {
        List<Map.Entry<UUID, CompanionRegistry.Entry>> owned = new ArrayList<>(
            CompanionRegistry.get(server).ownedBy(owner.getUUID())
        );
        for (Map.Entry<UUID, CompanionRegistry.Entry> entry : owned) {
            if (entry.getValue().diedAt() <= 0L
                && NumenPlayer.findByUuid(server, entry.getKey()) == null) {
                Companions.respawn(server, entry.getKey());
            }
        }
        Companions.syncRosterToOwner(server, owner);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer owner)
            || owner instanceof NumenPlayer) {
            return;
        }

        MinecraftServer server = owner.level().getServer();
        if (server == null
            || server.isDedicatedServer()
            || owner.connection == null
            || owner.connection.getConnection() == null
            || !owner.connection.getConnection().isMemoryConnection()) {
            return;
        }

        UUID ownerUuid = owner.getUUID();
        synchronized (NumenRuntimeFixes.class) {
            Set<UUID> pending = PENDING_RESTORES.get(server);
            if (pending != null) {
                pending.remove(ownerUuid);
                if (pending.isEmpty()) {
                    PENDING_RESTORES.remove(server);
                }
            }
        }

        List<NumenPlayer> ownedCompanions = new ArrayList<>();
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player instanceof NumenPlayer companion
                && ownerUuid.equals(companion.getOwnerUuid())) {
                ownedCompanions.add(companion);
            }
        }

        for (NumenPlayer companion : ownedCompanions) {
            removeCompanion(server, companion, "owner logout");
        }

        System.out.println("[numen-fix] Stopping integrated server after owner logout.");
        server.halt(false);
    }

    public static synchronized void shutdown(MinecraftServer server) {
        if (!STOPPING.add(server)) {
            return;
        }
        PENDING_RESTORES.remove(server);

        List<NumenPlayer> companions = new ArrayList<>();
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player instanceof NumenPlayer companion) {
                companions.add(companion);
            }
        }

        for (NumenPlayer companion : companions) {
            removeCompanion(server, companion, "server shutdown");
        }
    }

    private static void removeCompanion(
        MinecraftServer server,
        NumenPlayer companion,
        String reason
    ) {
        TemporaryScaffoldController.shutdown(companion);
        try {
            CompanionTickDispatcher.cancelFor(companion);
        } catch (RuntimeException exception) {
            System.err.println(
                "[numen-fix] Failed to cancel companion task during " + reason + ": " + exception
            );
        }

        try {
            Companions.dormant(server, companion);
        } catch (RuntimeException exception) {
            System.err.println(
                "[numen-fix] Failed to make companion dormant during " + reason + ": " + exception
            );
            try {
                CompanionFactory.despawn(server, companion);
            } catch (RuntimeException fallbackException) {
                System.err.println(
                    "[numen-fix] Failed to despawn companion during " + reason + ": "
                        + fallbackException
                );
            }
        }

        if (server.getPlayerList().getPlayers().contains(companion)) {
            try {
                server.getPlayerList().remove(companion);
            } catch (RuntimeException exception) {
                System.err.println(
                    "[numen-fix] Failed to remove companion from player list during " + reason + ": "
                        + exception
                );
            }
        }
    }
}
