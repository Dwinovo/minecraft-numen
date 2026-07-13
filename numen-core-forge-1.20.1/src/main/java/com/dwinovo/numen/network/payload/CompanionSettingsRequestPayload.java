package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionPreferences;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record CompanionSettingsRequestPayload(UUID uuid, boolean save, String gameMode,
                                               double maxHealth, double attackDamage, double attackSpeed,
                                               double movementSpeed, double armor, double armorToughness,
                                               double knockbackResistance, double luck,
                                               boolean invulnerable, int respawnSeconds) {
    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "companion_settings_request");
    public void encode(FriendlyByteBuf b) { b.writeUUID(uuid); b.writeBoolean(save); b.writeUtf(gameMode == null ? "survival" : gameMode, 16); b.writeDouble(maxHealth); b.writeDouble(attackDamage); b.writeDouble(attackSpeed); b.writeDouble(movementSpeed); b.writeDouble(armor); b.writeDouble(armorToughness); b.writeDouble(knockbackResistance); b.writeDouble(luck); b.writeBoolean(invulnerable); b.writeVarInt(respawnSeconds); }
    public static CompanionSettingsRequestPayload decode(FriendlyByteBuf b) { return new CompanionSettingsRequestPayload(b.readUUID(), b.readBoolean(), b.readUtf(16), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean(), b.readVarInt()); }
    public static void handle(CompanionSettingsRequestPayload p, ServerPlayer owner) {
        var registryEntry = com.dwinovo.numen.entity.CompanionRegistry.get(owner.level.getServer()).find(p.uuid());
        if (registryEntry == null || !registryEntry.owner().equals(owner.getUUID())) return;
        CompanionPreferences store = CompanionPreferences.get(owner.level.getServer());
        CompanionPreferences.Values values = p.save()
                ? store.put(p.uuid(), new CompanionPreferences.Values(p.gameMode(), p.maxHealth(), p.attackDamage(), p.attackSpeed(), p.movementSpeed(), p.armor(), p.armorToughness(), p.knockbackResistance(), p.luck(), p.invulnerable(), p.respawnSeconds()))
                : store.get(p.uuid());
        NumenPlayer body = NumenPlayer.findByUuid(owner.level.getServer(), p.uuid());
        if (body != null) CompanionPreferences.apply(body, values);
        Services.NETWORK.sendToPlayer(owner, CompanionSettingsPayload.ID, new CompanionSettingsPayload(p.uuid(), values.gameMode(), values.maxHealth(), values.attackDamage(), values.attackSpeed(), values.movementSpeed(), values.armor(), values.armorToughness(), values.knockbackResistance(), values.luck(), values.invulnerable(), values.respawnSeconds()));
    }
}
