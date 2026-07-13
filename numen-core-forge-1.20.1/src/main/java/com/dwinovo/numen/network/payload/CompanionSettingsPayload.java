package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.data.ClientCompanionSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record CompanionSettingsPayload(UUID uuid, String gameMode, double maxHealth,
                                       double attackDamage, double attackSpeed, double movementSpeed,
                                       double armor, double armorToughness, double knockbackResistance,
                                       double luck, boolean invulnerable, int respawnSeconds) {
    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "companion_settings");
    public void encode(FriendlyByteBuf b) { b.writeUUID(uuid); b.writeUtf(gameMode, 16); b.writeDouble(maxHealth); b.writeDouble(attackDamage); b.writeDouble(attackSpeed); b.writeDouble(movementSpeed); b.writeDouble(armor); b.writeDouble(armorToughness); b.writeDouble(knockbackResistance); b.writeDouble(luck); b.writeBoolean(invulnerable); b.writeVarInt(respawnSeconds); }
    public static CompanionSettingsPayload decode(FriendlyByteBuf b) { return new CompanionSettingsPayload(b.readUUID(), b.readUtf(16), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readBoolean(), b.readVarInt()); }
    public static void handle(CompanionSettingsPayload p) { ClientCompanionSettings.put(p.uuid(), new ClientCompanionSettings.Snapshot(p.gameMode(), p.maxHealth(), p.attackDamage(), p.attackSpeed(), p.movementSpeed(), p.armor(), p.armorToughness(), p.knockbackResistance(), p.luck(), p.invulnerable(), p.respawnSeconds(), System.currentTimeMillis())); }
}
