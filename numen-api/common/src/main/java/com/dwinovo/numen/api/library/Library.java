package com.dwinovo.numen.api.library;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一位玩家看到的一种库,存在两处:<b>服务器目录</b>({@link #server},管理员可读可写,jar 自带的默认内容也在这里)和
 * <b>玩家目录</b>({@link #mine},本人可读可写)。读取只认这两处,同名(命名空间与名字都相同)时玩家目录的优先。
 * 玩家对服务器目录的条目只读:想改就 {@link #copyToMine 复制到我的}再改;想关就 {@link #setEnabled 开关},记在他自己的偏好里
 * ({@link Switches})。界面上服务器目录的条目叫"服务器提供",玩家目录的叫"我的"。
 *
 * <p><b>房主</b>(单人游戏的玩家、局域网开房的人)的"我的"就是服务器目录那一份——他就是这台服务器,不另有玩家目录。这时
 * {@link #merged} 为真:{@link #mine} 与 {@link #server} 是同一个货架,只有一组条目,没有"另一组里的同名"可言,
 * 也没有可复制的。条目属于哪一处看它的 {@link Entry#group},界面上叫什么由读它的一方按 {@link #merged} 定。
 *
 * <p>谁能写哪一处不在这里判——权限只在库协议那一处({@code Libraries});这里只做事。
 *
 * <p>线程:读任何线程;写在服务端主线程(落盘走各自根的车道,见 {@link Shelf})。
 */
public final class Library {

    /** 一条在哪一处:服务器目录,还是玩家目录。 */
    public enum Group { SERVER, MINE }

    /**
     * 一条。
     *
     * @param enabled    玩家没把它关掉
     * @param paired     另一处里也有同名的一条:服务器目录的这条被玩家目录的盖着,或玩家目录的这条盖着服务器目录的
     * @param provenance 服务器目录的条目与 jar 的关系(是不是自带的、改过没有、有没有新版);玩家目录的条目恒为 {@link Provenance#NONE}
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
    private final Shelf server;
    private final Shelf mine;
    private final Switches switches;
    private final Provenance.Source provenance;

    /** @param mine 玩家目录的货架;房主给的是 {@code server} 本身 */
    public Library(LibraryKind kind, Shelf server, Shelf mine, Switches switches, Provenance.Source provenance) {
        this.kind = kind;
        this.server = server;
        this.mine = mine;
        this.switches = switches;
        this.provenance = provenance;
    }

    public LibraryKind kind() {
        return kind;
    }

    /** 服务器目录那一处的存放处(管理员写它)。 */
    public Shelf server() {
        return server;
    }

    /** 玩家自己写的东西放的存放处(本人写它);房主的就是 {@link #server}。 */
    public Shelf mine() {
        return mine;
    }

    /** 玩家目录就是服务器目录(这位玩家是房主):只有一组条目。 */
    public boolean merged() {
        return mine == server;
    }

    /** 两处加起来改过多少次(见 {@link Shelf#version});开关不算,它们不动文件。 */
    public long version() {
        return merged() ? server.version() : server.version() + mine.version();
    }

    /** 重扫目录。 */
    public void reload() {
        server.reload();
        if (!merged()) {
            mine.reload();
        }
    }

    /** 一处里的全部,含被另一处盖着的与被关掉的。房主没有玩家目录那一处:它是空的。 */
    public List<Entry> group(Group group) {
        if (merged() && group == Group.MINE) {
            return List.of();
        }
        Shelf shelf = group == Group.SERVER ? server : mine;
        Shelf other = group == Group.SERVER ? mine : server;
        List<Entry> out = new ArrayList<>();
        for (Shelf.Item item : shelf.items()) {
            out.add(entry(group, item, !merged() && other.get(item.id()) != null));
        }
        return out;
    }

    /** 叠起来的结果:同名只留玩家目录的,含被关掉的(带着 {@link Entry#enabled})。 */
    public List<Entry> effective() {
        Map<EntryId, Entry> byId = new LinkedHashMap<>();
        for (Shelf.Item item : server.items()) {
            byId.put(item.id(), entry(Group.SERVER, item, !merged() && mine.get(item.id()) != null));
        }
        if (!merged()) {
            for (Shelf.Item item : mine.items()) {
                byId.put(item.id(), entry(Group.MINE, item, server.get(item.id()) != null));
            }
        }
        return new ArrayList<>(byId.values());
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

    /** 叫这个名字的那一条(玩家目录优先),不管开关;没有是 {@code null}。 */
    public Entry findAny(EntryId id) {
        if (id == null) {
            return null;
        }
        if (!merged()) {
            Shelf.Item mineItem = mine.get(id);
            if (mineItem != null) {
                return entry(Group.MINE, mineItem, server.get(id) != null);
            }
        }
        Shelf.Item serverItem = server.get(id);
        return serverItem == null ? null : entry(Group.SERVER, serverItem, false);
    }

    /**
     * 把服务器目录的一条原样复制到玩家目录(同名,所以从此玩家目录的盖着它)。
     *
     * @return 玩家目录的那一条;服务器目录里没有这一条、或这位玩家是房主(他的"我的"就是服务器目录,无从复制)是 {@code null}
     */
    public Entry copyToMine(EntryId id) {
        Shelf.Item source = merged() ? null : server.get(id);
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
                group == Group.SERVER ? provenance.of(kind, item) : Provenance.NONE);
    }
}
