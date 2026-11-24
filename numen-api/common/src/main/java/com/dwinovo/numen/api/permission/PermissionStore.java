package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.permission.Edit;
import com.dwinovo.numen.permission.Preset;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每主人一份:他手下每只同伴的设置({@link Settings}:八个档位加每种动作一张"不用问"的名单)。存在主世界的存档数据里,
 * 文件名带主人 UUID。命令、面板、她改设置的 API 与"以后都允许"都只经这里的公开方法改它。
 *
 * <p>设置是不可变的 {@link Settings},每次改动换一份新的:{@link Permission#gateFor} 在主线程取走引用,搜索线程拿着读,
 * 不会读到改了一半的表。没设过的同伴是出厂的({@link Settings#FACTORY})。
 *
 * <p>存档格式:{@code companions} 一张表,同伴 UUID → {@code tiers}(动作词 → 档位词)与 {@code names}(动作词 → 名字列表)。
 * 读不通的内容(写着此刻不认得的动作与档位的项,和更早的规则、模式那一种存档)记一条错误日志、不生效、不迁移;
 * 别的项照读,文件不因此重写,不认得的那些到这份存档下一次落盘时才没有。
 */
public final class PermissionStore extends SavedData {

    private static final String COMPANIONS = "companions";
    private static final String TIERS = "tiers";
    private static final String NAMES = "names";
    /** 更早的存档里的顶层键:规则表、模式与命中账。 */
    private static final List<String> OLD_KEYS = List.of("modes", "deny", "ask", "allow", "hits");

    private static final SavedData.Factory<PermissionStore> FACTORY = new SavedData.Factory<>(
            PermissionStore::new, PermissionStore::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, Settings> settings = new HashMap<>();
    /** 存档任何一处变了就叫一声({@link #of} 接上):面板据此把新表推给主人,见 {@link PermissionFeed}。 */
    private Runnable onChange = () -> { };

    /** 空的一份,不连世界存档;真正的存档由 {@link #of} 取,这个给不经服务器的使用(测试的替身)。 */
    public PermissionStore() {}

    public static PermissionStore of(MinecraftServer server, UUID owner) {
        PermissionStore store = server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_permissions_" + owner);
        store.onChange = () -> PermissionFeed.changed(owner);
        return store;
    }

    @Override
    public void setDirty(boolean dirty) {
        super.setDirty(dirty);
        if (dirty) {
            onChange.run();
        }
    }

    static PermissionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PermissionStore store = new PermissionStore();
        for (String old : OLD_KEYS) {
            if (tag.contains(old)) {
                Constants.LOG.error("[numen-permission] the permission save holds the older rules and modes ('{}'); "
                        + "they are not used and not migrated", old);
                break;
            }
        }
        CompoundTag companions = tag.getCompound(COMPANIONS);
        for (String key : companions.getAllKeys()) {
            UUID companion;
            try {
                companion = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                Constants.LOG.error("[numen-permission] the permission save has unreadable content: '{}' is not a companion id", key);
                continue;
            }
            store.settings.put(companion, read(companions.getCompound(key)));
        }
        return store;
    }

    private static Settings read(CompoundTag one) {
        Settings out = Settings.FACTORY;
        CompoundTag tiers = one.getCompound(TIERS);
        for (String word : tiers.getAllKeys()) {
            Verb verb = Verb.byWord(word);
            Tier tier = Tier.byWord(tiers.getString(word));
            if (verb == null || tier == null) {
                Constants.LOG.error("[numen-permission] the permission save has unreadable content: tier {}={}", word,
                        tiers.getString(word));
                continue;
            }
            out = out.withTier(verb, tier);
        }
        CompoundTag names = one.getCompound(NAMES);
        for (String word : names.getAllKeys()) {
            Verb verb = Verb.byWord(word);
            if (verb == null) {
                Constants.LOG.error("[numen-permission] the permission save has unreadable content: list of {}", word);
                continue;
            }
            for (Tag name : names.getList(word, Tag.TAG_STRING)) {
                if (!name.getAsString().isBlank()) {
                    out = out.withName(verb, name.getAsString());
                }
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag companions = new CompoundTag();
        settings.forEach((companion, one) -> {
            if (one.equals(Settings.FACTORY)) {
                return;
            }
            CompoundTag tiers = new CompoundTag();
            CompoundTag names = new CompoundTag();
            for (Verb verb : Verb.values()) {
                if (one.tier(verb) != verb.factory()) {
                    tiers.putString(verb.word(), one.tier(verb).word());
                }
                if (!one.names(verb).isEmpty()) {
                    ListTag list = new ListTag();
                    one.names(verb).forEach(name -> list.add(StringTag.valueOf(name)));
                    names.put(verb.word(), list);
                }
            }
            CompoundTag entry = new CompoundTag();
            entry.put(TIERS, tiers);
            entry.put(NAMES, names);
            companions.put(companion.toString(), entry);
        });
        tag.put(COMPANIONS, companions);
        return tag;
    }

    /** 这只同伴的设置;没设过是出厂的。 */
    public Settings settingsOf(UUID companion) {
        return settings.getOrDefault(companion, Settings.FACTORY);
    }

    /** 做一次改动。 */
    public void edit(UUID companion, Edit edit) {
        put(companion, edit.applyTo(settingsOf(companion)));
    }

    /** 套一个预设。 */
    public void preset(UUID companion, Preset preset) {
        put(companion, settingsOf(companion).with(preset));
    }

    /** "以后都允许":每一项记进它那种动作的名单,已经有的不重复。 */
    public void remember(UUID companion, List<Settings.Entry> entries) {
        Settings now = settingsOf(companion);
        for (Settings.Entry entry : entries) {
            now = now.withName(entry.verb(), entry.name());
        }
        put(companion, now);
    }

    private void put(UUID companion, Settings next) {
        if (!next.equals(settingsOf(companion))) {
            settings.put(companion, next);
            setDirty();
        }
    }
}
