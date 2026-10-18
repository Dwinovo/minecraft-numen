package com.dwinovo.numen.api.library;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一位玩家看到的一种库:两组叠在一起。<b>内置</b>是服务器目录里的(管理员可读可写),<b>我的</b>是玩家目录里的(本人可读可写);
 * 读取只认这两个目录,同名(命名空间与名字都相同)时我的优先。玩家对内置只读:想改就 {@link #copyToMine 复制到我的}再改;
 * 想关就 {@link #setEnabled 开关},记在他自己的偏好里({@link Switches})。
 *
 * <p>谁能写哪一组不在这里判——权限只在库协议那一处({@code Libraries});这里只做事。
 *
 * <p>线程:读任何线程;写在服务端主线程(落盘走各自根的车道,见 {@link Shelf})。
 */
public final class Library {

    /** 一条在哪一组。 */
    public enum Group { BUILTIN, MINE }

    /**
     * 一条。
     *
     * @param enabled    玩家没把它关掉
     * @param paired     另一组里也有同名的一条:内置的这条被我的盖着,或我的这条盖着内置的
     * @param provenance 内置组的条目与 jar 的关系(改过没有、有没有新版);我的组恒为 {@link Provenance#NONE}
     */
    public record Entry(EntryId id, Group group, Shelf.Item item, boolean enabled, boolean paired,
                        Provenance provenance) {

        /** 文件型的正文。 */
        public String text() {
            return item.text();
        }

        /** 文件型是那个文件,目录型是那个目录。 */
        public Path path() {
            return item.path();
        }
    }

    private final LibraryKind kind;
    private final Shelf builtin;
    private final Shelf mine;
    private final Switches switches;
    private final Provenance.Source provenance;

    public Library(LibraryKind kind, Shelf builtin, Shelf mine, Switches switches, Provenance.Source provenance) {
        this.kind = kind;
        this.builtin = builtin;
        this.mine = mine;
        this.switches = switches;
        this.provenance = provenance;
    }

    public LibraryKind kind() {
        return kind;
    }

    /** 内置那一组的存放处(管理员写它)。 */
    public Shelf builtin() {
        return builtin;
    }

    /** 我的那一组的存放处(本人写它)。 */
    public Shelf mine() {
        return mine;
    }

    /** 两组加起来改过多少次(见 {@link Shelf#version});开关不算,它们不动文件。 */
    public long version() {
        return builtin.version() + mine.version();
    }

    /** 重扫两个目录。 */
    public void reload() {
        builtin.reload();
        mine.reload();
    }

    /** 一组里的全部,含被另一组盖着的与被关掉的。 */
    public List<Entry> group(Group group) {
        Shelf shelf = group == Group.BUILTIN ? builtin : mine;
        Shelf other = group == Group.BUILTIN ? mine : builtin;
        List<Entry> out = new ArrayList<>();
        for (Shelf.Item item : shelf.items()) {
            out.add(entry(group, item, other.get(item.id()) != null));
        }
        return out;
    }

    /** 叠起来的结果:同名只留我的,含被关掉的(带着 {@link Entry#enabled})。 */
    public List<Entry> effective() {
        Map<EntryId, Entry> merged = new LinkedHashMap<>();
        for (Shelf.Item item : builtin.items()) {
            merged.put(item.id(), entry(Group.BUILTIN, item, mine.get(item.id()) != null));
        }
        for (Shelf.Item item : mine.items()) {
            merged.put(item.id(), entry(Group.MINE, item, builtin.get(item.id()) != null));
        }
        return new ArrayList<>(merged.values());
    }

    /** 此刻在用的:叠起来的结果里没被关掉的。消费方(提示词、程序、模型调用)读的是它。 */
    public List<Entry> active() {
        return effective().stream().filter(Entry::enabled).toList();
    }

    /** 叫这个名字、此刻在用的那一条;没有或被关掉是 {@code null}。 */
    public Entry find(EntryId id) {
        Entry entry = findAny(id);
        return entry != null && entry.enabled() ? entry : null;
    }

    /** 叫这个名字的那一条(我的优先),不管开关;没有是 {@code null}。 */
    public Entry findAny(EntryId id) {
        if (id == null) {
            return null;
        }
        Shelf.Item mineItem = mine.get(id);
        if (mineItem != null) {
            return entry(Group.MINE, mineItem, builtin.get(id) != null);
        }
        Shelf.Item builtinItem = builtin.get(id);
        return builtinItem == null ? null : entry(Group.BUILTIN, builtinItem, false);
    }

    /**
     * 把内置的一条原样复制到我的(同名,所以从此我的盖着它)。
     *
     * @return 我的那一条;内置里没有这一条是 {@code null}
     */
    public Entry copyToMine(EntryId id) {
        Shelf.Item source = builtin.get(id);
        if (source == null) {
            return null;
        }
        Shelf.Item copy = kind.isDirectory() ? mine.copyIn(id, source.path()) : mine.write(id, source.text());
        return entry(Group.MINE, copy, true);
    }

    public boolean enabled(EntryId id) {
        return switches.enabled(kind.dir(), id);
    }

    public void setEnabled(EntryId id, boolean enabled) {
        switches.set(kind.dir(), id, enabled);
    }

    private Entry entry(Group group, Shelf.Item item, boolean paired) {
        return new Entry(item.id(), group, item, enabled(item.id()), paired,
                group == Group.BUILTIN ? provenance.of(kind, item) : Provenance.NONE);
    }
}
