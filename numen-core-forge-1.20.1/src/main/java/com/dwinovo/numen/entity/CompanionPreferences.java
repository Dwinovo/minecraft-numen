package com.dwinovo.numen.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-companion owner-editable gameplay attributes, persisted with the world. */
public final class CompanionPreferences extends SavedData {
    public record Values(String gameMode, double maxHealth, double attackDamage,
                         double attackSpeed, double movementSpeed, double armor,
                         double armorToughness, double knockbackResistance, double luck,
                         boolean invulnerable, int respawnSeconds) {
        public Values {
            gameMode = normalizeMode(gameMode);
            maxHealth = finite(maxHealth, 20, 1, 2048);
            attackDamage = finite(attackDamage, 1, 0, 2048);
            attackSpeed = finite(attackSpeed, 4, 0.1, 1024);
            movementSpeed = finite(movementSpeed, 0.1, 0.02, 1);
            armor = finite(armor, 0, 0, 100);
            armorToughness = finite(armorToughness, 0, 0, 100);
            knockbackResistance = finite(knockbackResistance, 0, 0, 1);
            luck = finite(luck, 0, -1024, 1024);
            respawnSeconds = Math.max(0, Math.min(3600, respawnSeconds));
        }
        public static Values defaults() { return new Values("survival",20,1,4,0.1,0,0,0,0,false,30); }
    }

    private final Map<UUID, Values> values = new HashMap<>();
    public static CompanionPreferences get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(CompanionPreferences::load, CompanionPreferences::new, "numen_companion_preferences"); }
    public Values get(UUID id) { return values.getOrDefault(id, Values.defaults()); }
    public Values put(UUID id, Values value) { Values normalized = new Values(value.gameMode(), value.maxHealth(), value.attackDamage(), value.attackSpeed(), value.movementSpeed(), value.armor(), value.armorToughness(), value.knockbackResistance(), value.luck(), value.invulnerable(), value.respawnSeconds()); values.put(id, normalized); setDirty(); return normalized; }

    public static void apply(NumenPlayer player, Values value) {
        player.setGameMode(switch (normalizeMode(value.gameMode())) { case "creative" -> GameType.CREATIVE; case "adventure" -> GameType.ADVENTURE; case "spectator" -> GameType.SPECTATOR; default -> GameType.SURVIVAL; });
        set(player, Attributes.MAX_HEALTH, value.maxHealth());
        set(player, Attributes.ATTACK_DAMAGE, value.attackDamage());
        set(player, Attributes.ATTACK_SPEED, value.attackSpeed());
        set(player, Attributes.MOVEMENT_SPEED, value.movementSpeed());
        set(player, Attributes.ARMOR, value.armor());
        set(player, Attributes.ARMOR_TOUGHNESS, value.armorToughness());
        set(player, Attributes.KNOCKBACK_RESISTANCE, value.knockbackResistance());
        set(player, Attributes.LUCK, value.luck());
        player.setHealth(Math.max(1, Math.min(player.getHealth(), (float) value.maxHealth())));
        player.setInvulnerable(value.invulnerable());
    }

    private static void set(NumenPlayer player, Attribute attribute, double value) { var instance=player.getAttribute(attribute); if(instance!=null) instance.setBaseValue(value); }

    @Override public CompoundTag save(CompoundTag tag) {
        CompoundTag all=new CompoundTag();
        for(var e:values.entrySet()) {
            Values value=e.getValue(); CompoundTag v=new CompoundTag();
            v.putString("gameMode",value.gameMode()); v.putDouble("maxHealth",value.maxHealth());
            v.putDouble("attackDamage",value.attackDamage()); v.putDouble("attackSpeed",value.attackSpeed());
            v.putDouble("movementSpeed",value.movementSpeed()); v.putDouble("armor",value.armor());
            v.putDouble("armorToughness",value.armorToughness()); v.putDouble("knockbackResistance",value.knockbackResistance());
            v.putDouble("luck",value.luck()); v.putBoolean("invulnerable",value.invulnerable());
            v.putInt("respawnSeconds",value.respawnSeconds()); all.put(e.getKey().toString(),v);
        }
        tag.put("values",all); return tag;
    }

    private static CompanionPreferences load(CompoundTag tag) {
        CompanionPreferences out=new CompanionPreferences(); CompoundTag all=tag.getCompound("values");
        for(String key:all.getAllKeys()) try {
            CompoundTag v=all.getCompound(key);
            out.values.put(UUID.fromString(key),new Values(v.getString("gameMode"),d(v,"maxHealth",20),d(v,"attackDamage",1),d(v,"attackSpeed",4),d(v,"movementSpeed",0.1),d(v,"armor",0),d(v,"armorToughness",0),d(v,"knockbackResistance",0),d(v,"luck",0),v.getBoolean("invulnerable"),v.contains("respawnSeconds")?v.getInt("respawnSeconds"):30));
        } catch(RuntimeException ignored) {}
        return out;
    }

    private static double d(CompoundTag tag,String key,double fallback){return tag.contains(key)?tag.getDouble(key):fallback;}
    private static double finite(double value,double fallback,double min,double max){if(!Double.isFinite(value))value=fallback;return Math.max(min,Math.min(max,value));}
    private static String normalizeMode(String mode){if(mode==null)return "survival";return switch(mode.toLowerCase()){case "creative","adventure","spectator"->mode.toLowerCase();default->"survival";};}
}
