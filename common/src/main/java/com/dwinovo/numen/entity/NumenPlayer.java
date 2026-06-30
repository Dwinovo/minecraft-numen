package com.dwinovo.numen.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * The companion body: a server-side fake {@link ServerPlayer}. Replaces the old
 * custom {@code NumenEntity} Mob so the companion is a first-class player —
 * native interaction/combat code paths (universal mod compatibility), its own
 * player inventory, and free chunk loading + playerdata persistence by virtue of
 * being a list-resident player.
 *
 * <h2>Identity &amp; ownership</h2>
 * Created by {@link CompanionFactory} with a stable per-companion UUID (carried
 * in the {@link GameProfile}); the enumerable index lives in
 * {@link CompanionRegistry}. Unlike the Mob, a fake player cannot carry custom
 * {@code SynchedEntityData}, so the owner is a plain server-side field persisted
 * to the companion's own playerdata {@code .dat} via
 * {@link #addAdditionalSaveData}. Owner checks are UUID comparisons — never
 * vanilla {@code isOwnedBy} (which resolves through a level and breaks across
 * dimensions).
 */
public final class NumenPlayer extends ServerPlayer {

    private static final String NBT_KEY_OWNER = "NumenOwner";

    /** Owner's player UUID. Null only transiently before the first assignment. */
    private UUID ownerUuid;

    /** Latched once we've handled this body's death, so the post-death routine runs exactly once. */
    private boolean deathHandled;

    public NumenPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                        ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
    }

    /** The loaded companion body with this UUID, or {@code null} if not spawned. */
    public static NumenPlayer findByUuid(MinecraftServer server, UUID uuid) {
        return server.getPlayerList().getPlayer(uuid) instanceof NumenPlayer ap ? ap : null;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    /** Cross-dimension safe owner check — UUID comparison, not level-scoped lookup. */
    public boolean isOwnedByPlayer(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    /** The owner as an online player, server-wide; null when offline. */
    public ServerPlayer resolveOwnerPlayer() {
        return ownerUuid == null ? null : level().getServer().getPlayerList().getPlayer(ownerUuid);
    }


    /** True if {@code item} sits anywhere in the inventory (hotbar/main/offhand all count). */
    public boolean ensureInInventory(Item item) {
        var inv = getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    /**
     * Hold the item in inventory slot {@code slot} in the main hand the way a real player
     * does — a hotbar slot is simply SELECTED (number-key); a main-inventory slot is SWAPPED
     * into the currently selected hotbar slot (item-conserving). This is the only correct way
     * to "switch to hand": calling {@code setItemInHand(MAIN_HAND, stack)} overwrites the held
     * item (losing it) and aliases ONE {@link net.minecraft.world.item.ItemStack} across two
     * slots, which corrupts the inventory once the stack is consumed. No-op for {@code slot < 0}.
     */
    public void holdInHand(int slot) {
        if (slot < 0) {
            return;
        }
        var inv = getInventory();
        if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
            return;
        }
        int selected = inv.selected;
        net.minecraft.world.item.ItemStack held = inv.getItem(selected);
        inv.setItem(selected, inv.getItem(slot));
        inv.setItem(slot, held);
    }

    // ---- server tick (Carpet's EntityPlayerMPFake trick) ----

    /**
     * Drive the body's own movement physics. A real {@link ServerPlayer} runs
     * {@code travel} (against {@code zza}/{@code xxa}), food, air and pose inside
     * {@link #doTick()}, which the network layer invokes via
     * {@code connection.tick()}. A fake player's connection is a no-op, so
     * {@code doTick()} never fires and the body would only ever turn (a direct
     * {@code setYRot} write) without walking. The entity system already calls
     * {@code super.tick()} (menus / container / position sync), so we add the
     * missing {@code doTick()} movement pass here — exactly as Carpet's
     * {@code EntityPlayerMPFake.tick()} does. Every 10 ticks we resync the
     * connection position and let chunk loading follow the body so it never
     * walks out of its loaded area.
     */
    @Override
    public void tick() {
        // A fake player isn't auto-removed on death (no client to send a respawn packet), so it would
        // sit at 0 HP forever. Detect death once, hand off to the recoverable-death routine (stop the
        // brain, schedule a respawn at the owner), and skip the normal movement/AI tick for this corpse.
        if (!deathHandled && (getHealth() <= 0.0f || isDeadOrDying())) {
            deathHandled = true;
            Companions.onDeath(this);
            return;
        }
        if (level() instanceof ServerLevel sl && sl.getGameTime() % 10 == 0) {
            this.connection.resetPosition();
            sl.getChunkSource().move(this);
        }
        super.tick();
        try {
            this.doTick();
        } catch (Exception ignored) {
            // mirrors Carpet — fake-connection internals can NPE on edge cases
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag output) {
        super.addAdditionalSaveData(output);
        if (ownerUuid != null) {
            output.putUUID(NBT_KEY_OWNER, ownerUuid);   // 1.21.4: no CompoundTag.store(Codec)
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag input) {
        super.readAdditionalSaveData(input);
        if (input.hasUUID(NBT_KEY_OWNER)) this.ownerUuid = input.getUUID(NBT_KEY_OWNER);
    }
}
