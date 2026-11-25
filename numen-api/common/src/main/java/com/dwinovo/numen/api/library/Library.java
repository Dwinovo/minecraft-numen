package com.dwinovo.numen.api.library;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 主人这台电脑上的一种库:一个货架({@link Shelf}),随模组带来的默认内容({@link Shipped})与他自己写的在同一份里,条目是不是
 * 自带的、改过没有、有没有新版看清单({@link Provenance}),他想关掉的条目用开关({@link Switches},记在他的偏好里)。
 *
 * <p>线程:读任何线程;写在大脑的线程(落盘走货架根的车道,见 {@link Shelf})。
 */
public final class Library {

    /**
     * 一条。
     *
     * @param enabled    主人没把它关掉
     * @param provenance 它与 jar 的关系(是不是自带的、改过没有、有没有新版)
     */
    public record Entry(EntryId id, Shelf.Item item, boolean enabled, Provenance provenance) {

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
    private final Shelf shelf;
    private final Switches switches;
    private final Provenance.Source provenance;

    public Library(LibraryKind kind, Shelf shelf, Switches switches, Provenance.Source provenance) {
        this.kind = kind;
        this.shelf = shelf;
        this.switches = switches;
        this.provenance = provenance;
    }

    public LibraryKind kind() {
        return kind;
    }

    /** 这种库的存放处。 */
    public Shelf shelf() {
        return shelf;
    }

    /** 改过多少次(见 {@link Shelf#version});开关不算,它们不动文件。 */
    public long version() {
        return shelf.version();
    }

    /** 重扫目录。 */
    public void reload() {
        shelf.reload();
    }

    /** 全部条目,含被关掉的(带着 {@link Entry#enabled})。 */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Shelf.Item item : shelf.items()) {
            out.add(entry(item));
        }
        return out;
    }

    /** 此刻在用的:没被关掉的。消费方(提示词、程序、模型调用)读的是它。 */
    public List<Entry> active() {
        return entries().stream().filter(Entry::enabled).toList();
    }

    /** 叫这个名字、此刻在用的那一条;没有或被关掉是 {@code null}。 */
    public Entry find(EntryId id) {
        Entry entry = findAny(id);
        return entry != null && entry.enabled() ? entry : null;
    }

    /** 叫这个名字的那一条,不管开关;没有是 {@code null}。 */
    public Entry findAny(EntryId id) {
        Shelf.Item item = shelf.get(id);
        return item == null ? null : entry(item);
    }

    public boolean enabled(EntryId id) {
        return switches.enabled(kind.dir(), id);
    }

    public void setEnabled(EntryId id, boolean enabled) {
        switches.set(kind.dir(), id, enabled);
    }

    private Entry entry(Shelf.Item item) {
        return new Entry(item.id(), item, enabled(item.id()), provenance.of(kind, item));
    }
}
