package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Verdict;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 每主人一份:他手下每只同伴的{@link Mode 模式},他自己写的那一层规则(deny、ask、allow 三张表,每一行记着谁写的),
 * 和规则的命中账。存在主世界的存档数据里,文件名带主人 UUID。命令、她改规则的 API、"允许并记住"与以后的面板都只经这里的公开方法改它。
 *
 * <p>规则层是不可变的 {@link RuleSet},每次改动换一份新的:{@link Permission#gateFor} 在主线程取走引用,
 * 搜索线程拿着读,不会读到改了一半的表。每一行都经 {@link Groups#parse} 解析,存档里写错的行读档时记一条
 * 错误日志、不进表。
 *
 * <h2>命中账</h2>
 * 每条规则(硬规则、主人层、出厂层,按层名+表+原文认)记命中次数与最近一次命中的时间({@link Hits})。
 * 主人层的一行被删掉,它的账一起清零;改了规则就是另一行,从零数起。
 */
public final class PermissionStore extends SavedData {

    /** 一行是谁写的。 */
    public enum Source implements StringRepresentable {
        /** 主人:命令、面板,或点了"允许并记住"。 */
        OWNER("owner"),
        /** 她:收紧的行(deny、ask)由她直接加,放宽的行(allow)经主人在征询卡上点头。 */
        HER("her");

        private final String word;

        Source(String word) {
            this.word = word;
        }

        @Override
        public String getSerializedName() {
            return word;
        }
    }

    /**
     * 主人层的一行。
     *
     * @param rule   规则
     * @param source 谁写的
     * @param note   写的人说的理由;没有为空串
     */
    public record Row(Rule rule, Source source, String note) {
        static Row byOwner(Rule rule) {
            return new Row(rule, Source.OWNER, "");
        }
    }

    /**
     * 一条规则的命中账。
     *
     * @param count      命中几次
     * @param lastMillis 最近一次命中的时刻(墙钟毫秒);没命中过为 0
     */
    public record Hit(int count, long lastMillis) {
        public static final Hit NEVER = new Hit(0, 0);
    }

    /** 存档里的一行规则;写错的行解码失败,消息与 {@link Rule#parse} 同一份。 */
    private static final Codec<Rule> RULE = Codec.STRING.comapFlatMap(text -> {
        try {
            return DataResult.success(Groups.parse(text));
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }, Rule::toString);

    private static final Codec<Row> ROW_OBJECT = RecordCodecBuilder.create(i -> i.group(
            RULE.fieldOf("rule").forGetter(Row::rule),
            StringRepresentable.fromEnum(Source::values).optionalFieldOf("source", Source.OWNER)
                    .forGetter(Row::source),
            Codec.STRING.optionalFieldOf("note", "").forGetter(Row::note)
    ).apply(i, Row::new));

    /** 一行:带来源的对象,或者只有规则原文(主人写的)。 */
    private static final Codec<Row> ROW = Codec.withAlternative(ROW_OBJECT, RULE.xmap(Row::byOwner, Row::rule));

    private static final Codec<List<Row>> TABLE = ROW.listOf();

    private record Account(String key, int count, long last) {
        static final Codec<Account> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("key").forGetter(Account::key),
                Codec.INT.fieldOf("count").forGetter(Account::count),
                Codec.LONG.fieldOf("last").forGetter(Account::last)
        ).apply(i, Account::new));
    }

    private static final Codec<PermissionStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING)
                    .fieldOf("modes").forGetter(PermissionStore::modeNames),
            TABLE.optionalFieldOf("deny", List.of()).forGetter(s -> s.tables.get(Verdict.Kind.DENY)),
            TABLE.optionalFieldOf("ask", List.of()).forGetter(s -> s.tables.get(Verdict.Kind.ASK)),
            TABLE.optionalFieldOf("allow", List.of()).forGetter(s -> s.tables.get(Verdict.Kind.ALLOW)),
            Account.CODEC.listOf().optionalFieldOf("hits", List.of()).forGetter(PermissionStore::accounts)
    ).apply(i, PermissionStore::new));

    private static final SavedData.Factory<PermissionStore> FACTORY = new SavedData.Factory<>(
            PermissionStore::new, PermissionStore::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, Mode> modes = new HashMap<>();
    /** 三张表的行;每次改动换一份新的列表。 */
    private final Map<Verdict.Kind, List<Row>> tables = new EnumMap<>(Verdict.Kind.class);
    private RuleSet rules = RuleSet.EMPTY;
    private final Map<String, Hit> hits = new HashMap<>();
    /** 存档任何一处变了就叫一声({@link #of} 接上):面板据此把新表推给主人,见 {@link PermissionFeed}。 */
    private Runnable onChange = () -> { };

    /** 空的一份,不连世界存档;真正的存档由 {@link #of} 取,这个给不经服务器的使用(测试的替身)。 */
    public PermissionStore() {
        for (Verdict.Kind table : Verdict.Kind.values()) {
            tables.put(table, List.of());
        }
    }

    private PermissionStore(Map<UUID, String> modeNames, List<Row> deny, List<Row> ask, List<Row> allow,
                            List<Account> accounts) {
        this();
        modeNames.forEach((uuid, name) -> modes.put(uuid, Mode.byName(name)));
        tables.put(Verdict.Kind.DENY, List.copyOf(deny));
        tables.put(Verdict.Kind.ASK, List.copyOf(ask));
        tables.put(Verdict.Kind.ALLOW, List.copyOf(allow));
        accounts.forEach(a -> hits.put(a.key(), new Hit(a.count(), a.last())));
        rules = rulesOf(tables);
    }

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
        boolean rewritten = migrate(tag);
        PermissionStore store = CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(error -> Constants.LOG.error("[numen-permission] 权限存档有读不懂的内容: {}", error))
                .orElseGet(PermissionStore::new);
        if (rewritten) {
            store.setDirty();
        }
        return store;
    }

    /**
     * 旧行改写成新名字({@link RuleMigration}),每改一行记一条日志。
     *
     * @return 有没有改过——改过的存档要重新落盘
     */
    private static boolean migrate(CompoundTag tag) {
        boolean rewritten = false;
        for (String table : List.of("deny", "ask", "allow")) {
            if (!(tag.get(table) instanceof ListTag rows)) {
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                Tag row = rows.get(i);
                CompoundTag holder = row instanceof CompoundTag c ? c : null;
                String old = holder != null ? holder.getString("rule") : row.getAsString();
                String now = RuleMigration.rewrite(old);
                if (!now.equals(old)) {
                    Constants.LOG.info("[numen-permission] 旧规则 {} {} 改写成 {}", table, old, now);
                    if (holder != null) {
                        holder.putString("rule", now);
                    } else {
                        rows.set(i, StringTag.valueOf(now));
                    }
                    rewritten = true;
                }
            }
        }
        return rewritten;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
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

    /** 主人这一层某张表的行,带来源与理由,按写的先后。 */
    public List<Row> rows(Verdict.Kind table) {
        return tables.get(table);
    }

    /**
     * 主人在一张表末尾加一行。
     *
     * @return 加上了;这张表里已经有一模一样的一行时不重复加,返回 false
     */
    public boolean add(Verdict.Kind table, Rule rule) {
        return add(table, new Row(rule, Source.OWNER, ""));
    }

    /**
     * 在一张表末尾加一行。
     *
     * @return 加上了;这张表里已经有一模一样的规则时不重复加,返回 false
     */
    public boolean add(Verdict.Kind table, Row row) {
        List<Row> rows = tables.get(table);
        if (rows.stream().anyMatch(r -> r.rule().equals(row.rule()))) {
            return false;
        }
        List<Row> next = new ArrayList<>(rows);
        next.add(row);
        replace(table, next);
        return true;
    }

    /**
     * 删掉一张表里的第 {@code index} 行(从 0 数),它的命中账一起清掉。
     *
     * @throws IndexOutOfBoundsException 这张表没有这一行
     */
    public Rule remove(Verdict.Kind table, int index) {
        List<Row> next = new ArrayList<>(tables.get(table));
        Rule removed = next.remove(index).rule();
        hits.remove(key(Layers.OWNER, table, removed));
        replace(table, next);
        return removed;
    }

    /** 这张表里这一行规则的位置(从 0 数);没有为 -1。 */
    public int indexOf(Verdict.Kind table, Rule rule) {
        List<Row> rows = tables.get(table);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).rule().equals(rule)) {
                return i;
            }
        }
        return -1;
    }

    /** 清空主人这一层的三张表和它们的命中账;出厂层、硬规则的账与各同伴的模式不动。 */
    public void reset() {
        for (Verdict.Kind table : Verdict.Kind.values()) {
            tables.get(table).forEach(row -> hits.remove(key(Layers.OWNER, table, row.rule())));
            tables.put(table, List.of());
        }
        rules = RuleSet.EMPTY;
        setDirty();
    }

    /** "允许并记住":每一行进 allow 表,已经有的不重复。 */
    public void remember(List<Rule> allow) {
        for (Rule rule : allow) {
            add(Verdict.Kind.ALLOW, rule);
        }
    }

    // ==================== 命中账 ====================

    /** 一条规则命中一次。 */
    public void hit(int layer, Verdict.Kind table, Rule rule) {
        hits.merge(key(layer, table, rule), new Hit(1, System.currentTimeMillis()),
                (old, once) -> new Hit(old.count() + 1, once.lastMillis()));
        setDirty();
    }

    /** 一条规则的命中账;没命中过是 {@link Hit#NEVER}。 */
    public Hit hitsOf(int layer, Verdict.Kind table, Rule rule) {
        return hits.getOrDefault(key(layer, table, rule), Hit.NEVER);
    }

    private static String key(int layer, Verdict.Kind table, Rule rule) {
        return Layers.name(layer) + "|" + table.name().toLowerCase(Locale.ROOT) + "|" + rule;
    }

    private List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        hits.forEach((key, hit) -> out.add(new Account(key, hit.count(), hit.lastMillis())));
        return out;
    }

    // ==================== 内部 ====================

    private void replace(Verdict.Kind table, List<Row> next) {
        tables.put(table, List.copyOf(next));
        rules = rulesOf(tables);
        setDirty();
    }

    private static RuleSet rulesOf(Map<Verdict.Kind, List<Row>> tables) {
        return new RuleSet(rulesIn(tables.get(Verdict.Kind.DENY)), rulesIn(tables.get(Verdict.Kind.ASK)),
                rulesIn(tables.get(Verdict.Kind.ALLOW)));
    }

    private static List<Rule> rulesIn(List<Row> rows) {
        return rows.stream().map(Row::rule).toList();
    }

    private Map<UUID, String> modeNames() {
        Map<UUID, String> out = new HashMap<>();
        modes.forEach((uuid, mode) -> out.put(uuid, mode.name().toLowerCase(Locale.ROOT)));
        return out;
    }
}
