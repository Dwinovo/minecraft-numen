package com.dwinovo.numen.script;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.cli.Names;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 每主人一份:同伴们存下的脚本,和每份脚本(连同内置的)跑过的战绩。脚本归主人,不归哪一只同伴——同一主人的同伴都看得见、
 * 都跑得了,跟着存档走,重启不丢。存在主世界的存档数据里,文件名带主人 UUID,与 {@code AreaStore} 同一个做法。
 *
 * <p>线程:主线程读写。手里的表是不可变快照,每次改动换一份新的。
 *
 * <p>读档时读不通的一份(名字不合规矩)记一条错误日志、不进表,与读不通的区域同一个处理。
 */
public final class ScriptStore extends SavedData {

    private static final SavedData.Factory<ScriptStore> FACTORY = new SavedData.Factory<>(
            ScriptStore::new, ScriptStore::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    /**
     * 一份存下的脚本。
     *
     * @param code       正文
     * @param summary    一句话说明(正文开头那行注释)
     * @param author     存它的同伴
     * @param authorName 存它的同伴当时叫什么(回执与清单里点名用)
     * @param savedAt    存下的时刻,epoch 毫秒
     */
    public record Saved(String code, String summary, UUID author, String authorName, long savedAt) {}

    /**
     * 一份脚本跑过的战绩:只记事实,不替她评判。
     *
     * @param runs        跑了几次
     * @param ok          跑到最后的几次
     * @param lastRun     最近一次的时刻,epoch 毫秒;没跑过是 0
     * @param failedLine  最近一次没跑完停在哪一行;没失败过是 0
     * @param failedWhy   最近一次没跑完的原因;没失败过是 null
     */
    public record Stats(int runs, int ok, long lastRun, int failedLine, String failedWhy) {

        static final Stats NONE = new Stats(0, 0, 0, 0, null);

        Stats with(boolean succeeded, int line, String why, long at) {
            return succeeded ? new Stats(runs + 1, ok + 1, at, failedLine, failedWhy)
                    : new Stats(runs + 1, ok, at, line, why);
        }
    }

    private SortedMap<String, Saved> saved = Collections.emptySortedMap();
    private SortedMap<String, Stats> stats = Collections.emptySortedMap();

    ScriptStore() {
    }

    public static ScriptStore of(MinecraftServer server, UUID owner) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_scripts_" + owner);
    }

    static ScriptStore load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag);
    }

    static ScriptStore load(CompoundTag tag) {
        ScriptStore store = new ScriptStore();
        TreeMap<String, Saved> scripts = new TreeMap<>();
        CompoundTag all = tag.getCompound("scripts");
        for (String name : all.getAllKeys()) {
            if (!Names.valid(name)) {
                Constants.LOG.error("[numen-script] 脚本名 {} 不合规矩,没有载入", name);
                continue;
            }
            CompoundTag one = all.getCompound(name);
            scripts.put(name, new Saved(one.getString("code"), one.getString("summary"), one.getUUID("author"),
                    one.getString("author_name"), one.getLong("saved_at")));
        }
        TreeMap<String, Stats> runs = new TreeMap<>();
        CompoundTag record = tag.getCompound("stats");
        for (String name : record.getAllKeys()) {
            CompoundTag one = record.getCompound(name);
            runs.put(name, new Stats(one.getInt("runs"), one.getInt("ok"), one.getLong("last_run"),
                    one.getInt("failed_line"), one.contains("failed_why") ? one.getString("failed_why") : null));
        }
        store.saved = Collections.unmodifiableSortedMap(scripts);
        store.stats = Collections.unmodifiableSortedMap(runs);
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return save(tag);
    }

    CompoundTag save(CompoundTag tag) {
        CompoundTag all = new CompoundTag();
        saved.forEach((name, s) -> {
            CompoundTag one = new CompoundTag();
            one.putString("code", s.code());
            one.putString("summary", s.summary());
            one.putUUID("author", s.author());
            one.putString("author_name", s.authorName());
            one.putLong("saved_at", s.savedAt());
            all.put(name, one);
        });
        tag.put("scripts", all);
        CompoundTag record = new CompoundTag();
        stats.forEach((name, s) -> {
            CompoundTag one = new CompoundTag();
            one.putInt("runs", s.runs());
            one.putInt("ok", s.ok());
            one.putLong("last_run", s.lastRun());
            one.putInt("failed_line", s.failedLine());
            if (s.failedWhy() != null) {
                one.putString("failed_why", s.failedWhy());
            }
            record.put(name, one);
        });
        tag.put("stats", record);
        return tag;
    }

    /** 存下的全部脚本,按名字排;不可变快照。 */
    public SortedMap<String, Saved> saved() {
        return saved;
    }

    /** 叫这个名字的那份存下的脚本;没有是 null。 */
    public Saved get(String name) {
        return saved.get(name);
    }

    /** 这份脚本(存下的或内置的)的战绩;没跑过是全零。 */
    public Stats stats(String name) {
        return stats.getOrDefault(name, Stats.NONE);
    }

    /** 存一份(新的,或改掉同名的那份):正文变了,旧的战绩说的是旧正文,一并清掉。 */
    public void put(String name, Saved script) {
        Names.checked("script", name);
        TreeMap<String, Saved> next = new TreeMap<>(saved);
        next.put(name, script);
        saved = Collections.unmodifiableSortedMap(next);
        forget(name);
        setDirty();
    }

    /**
     * 删掉一份存下的脚本,连同它的战绩。
     *
     * @return 删掉的那份;没有叫这个名字的是 null
     */
    public Saved delete(String name) {
        if (!saved.containsKey(name)) {
            return null;
        }
        TreeMap<String, Saved> next = new TreeMap<>(saved);
        Saved removed = next.remove(name);
        saved = Collections.unmodifiableSortedMap(next);
        forget(name);
        setDirty();
        return removed;
    }

    /** 记一次运行。 */
    public void tally(String name, boolean ok, int line, String why, long at) {
        TreeMap<String, Stats> next = new TreeMap<>(stats);
        next.put(name, stats(name).with(ok, line, why, at));
        stats = Collections.unmodifiableSortedMap(next);
        setDirty();
    }

    private void forget(String name) {
        if (stats.containsKey(name)) {
            TreeMap<String, Stats> next = new TreeMap<>(stats);
            next.remove(name);
            stats = Collections.unmodifiableSortedMap(next);
        }
    }
}
