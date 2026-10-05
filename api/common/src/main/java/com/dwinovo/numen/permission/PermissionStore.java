package com.dwinovo.numen.permission;

import com.dwinovo.numen.Constants;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 每主人一份:他手下每只同伴的{@link Mode 模式},和他自己写的那一层规则(deny、ask、allow 三张表)。
 * 存在主世界的存档数据里,文件名带主人 UUID。命令、"允许并记住"与以后的面板都只经这里的公开方法改它。
 *
 * <p>规则层是不可变的 {@link RuleSet},每次改动换一份新的:{@link Permission#gateFor} 在主线程取走引用,
 * 搜索线程拿着读,不会读到改了一半的表。每一行都经 {@link Rule#parse} 解析,存档里写错的行读档时记一条
 * 错误日志、不进表。
 */
public final class PermissionStore extends SavedData {

    private static final Codec<List<Rule>> TABLE = Rule.CODEC.listOf();

    static final Codec<PermissionStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING)
                    .fieldOf("modes").forGetter(PermissionStore::modeNames),
            TABLE.optionalFieldOf("deny", List.of()).forGetter(s -> s.rules.deny()),
            TABLE.optionalFieldOf("ask", List.of()).forGetter(s -> s.rules.ask()),
            TABLE.optionalFieldOf("allow", List.of()).forGetter(s -> s.rules.allow())
    ).apply(i, PermissionStore::new));

    // 每只主人一份存档,名字带主人的 UUID;1.21.5 起存储层自己驱动 CODEC(解析失败记日志、退回空表)
    private static SavedDataType<PermissionStore> type(UUID owner) {
        return new SavedDataType<>("numen_permissions_" + owner, PermissionStore::new, CODEC,
                DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);
    }

    private final Map<UUID, Mode> modes = new HashMap<>();
    private RuleSet rules = RuleSet.EMPTY;

    PermissionStore() {
    }

    private PermissionStore(Map<UUID, String> modeNames, List<Rule> deny, List<Rule> ask, List<Rule> allow) {
        modeNames.forEach((uuid, name) -> modes.put(uuid, Mode.byName(name)));
        rules = new RuleSet(deny, ask, allow);
    }

    public static PermissionStore of(MinecraftServer server, UUID owner) {
        return server.overworld().getDataStorage().computeIfAbsent(type(owner));
    }

    /** 这只同伴的模式;没设过是 {@link Mode#ASK}。 */
    public Mode modeOf(UUID companion) {
        return modes.getOrDefault(companion, Mode.ASK);
    }

    public void setMode(UUID companion, Mode mode) {
        modes.put(companion, mode);
        setDirty();
    }

    /** 主人这一层此刻的规则(不可变快照)。 */
    public RuleSet rules() {
        return rules;
    }

    /**
     * 在一张表末尾加一行。
     *
     * @return 加上了;这张表里已经有一模一样的一行时不重复加,返回 false
     */
    public boolean add(Verdict.Kind table, Rule rule) {
        List<Rule> rows = rules.table(table);
        if (rows.contains(rule)) {
            return false;
        }
        List<Rule> next = new ArrayList<>(rows);
        next.add(rule);
        rules = rules.withTable(table, next);
        setDirty();
        return true;
    }

    /**
     * 删掉一张表里的第 {@code index} 行(从 0 数)。
     *
     * @throws IndexOutOfBoundsException 这张表没有这一行
     */
    public Rule remove(Verdict.Kind table, int index) {
        List<Rule> next = new ArrayList<>(rules.table(table));
        Rule removed = next.remove(index);
        rules = rules.withTable(table, next);
        setDirty();
        return removed;
    }

    /** 清空主人这一层的三张表;出厂层与各同伴的模式不动。 */
    public void reset() {
        rules = RuleSet.EMPTY;
        setDirty();
    }

    /** "允许并记住":每一行进 allow 表,已经有的不重复。 */
    public void remember(List<Rule> allow) {
        for (Rule rule : allow) {
            add(Verdict.Kind.ALLOW, rule);
        }
    }

    private Map<UUID, String> modeNames() {
        Map<UUID, String> out = new HashMap<>();
        modes.forEach((uuid, mode) -> out.put(uuid, mode.name().toLowerCase(Locale.ROOT)));
        return out;
    }
}
