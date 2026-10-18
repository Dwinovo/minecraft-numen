package com.dwinovo.numen.api.library;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Atomic;
import com.dwinovo.numen.api.store.Trees;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 一个根下一种库的内容:{@code <根>/<库>/<命名空间>/<名字>} 扫出来的那一张表。表在内存里,读不碰盘、可以在任何线程上读;
 * 写先改内存里的表(接着读马上看得到),再把落盘交给根的写车道({@link Home#io()})。手改磁盘上的文件要 {@link #reload} 才读到。
 *
 * <p>只认布局,不认条目里装的是什么;文件型的库({@link LibraryKind#files})把正文读成文字带在 {@link Item} 上。
 */
public final class Shelf {

    /**
     * 一条。
     *
     * @param path 文件型是那个文件,目录型是那个目录
     * @param text 文件型的正文;目录型是 {@code null}(里面的文件按需读)
     */
    public record Item(EntryId id, Path path, String text) {}

    private final LibraryKind kind;
    private final Home root;
    private volatile Map<EntryId, Item> items = Map.of();
    private volatile long version;

    public Shelf(LibraryKind kind, Home root) {
        this.kind = kind;
        this.root = root;
        reload();
    }

    public LibraryKind kind() {
        return kind;
    }

    /** 这种库在根下的目录。 */
    public Path dir() {
        return root.file(kind.dir());
    }

    /** 全部条目,按命名空间再按名字排。 */
    public Collection<Item> items() {
        return items.values();
    }

    public Item get(EntryId id) {
        return id == null ? null : items.get(id);
    }

    /** 每改一次(重扫、写、删)加一:从库的内容派生出东西的一方,据此知道要不要重新派生。 */
    public long version() {
        return version;
    }

    /** 重扫磁盘。读不了的一条跳过并记一笔。 */
    public synchronized void reload() {
        Map<EntryId, Item> next = new LinkedHashMap<>();
        Path dir = dir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> spaces = Files.list(dir)) {
                for (Path space : spaces.filter(Files::isDirectory).sorted().toList()) {
                    scanSpace(space, next);
                }
            } catch (IOException e) {
                Constants.LOG.warn("[numen-library] 扫不了 {}: {}", dir, e.toString());
            }
        }
        replace(next);
    }

    private void scanSpace(Path space, Map<EntryId, Item> into) throws IOException {
        String namespace = space.getFileName().toString();
        if (!EntryId.segment(namespace)) {
            return;
        }
        try (Stream<Path> children = Files.list(space)) {
            for (Path child : children.sorted().toList()) {
                String file = child.getFileName().toString();
                if (kind.isDirectory()) {
                    if (Files.isDirectory(child) && EntryId.segment(file) && Files.isRegularFile(child.resolve(kind.marker()))) {
                        EntryId id = new EntryId(namespace, file);
                        into.put(id, new Item(id, child, null));
                    }
                } else if (Files.isRegularFile(child) && file.endsWith(kind.suffix())) {
                    String name = file.substring(0, file.length() - kind.suffix().length());
                    if (EntryId.segment(name)) {
                        read(child, new EntryId(namespace, name), into);
                    }
                }
            }
        }
    }

    private static void read(Path file, EntryId id, Map<EntryId, Item> into) {
        try {
            into.put(id, new Item(id, file, Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            Constants.LOG.warn("[numen-library] 读不了 {}: {}", file, e.toString());
        }
    }

    /** 在 {@code namespace} 下、这个货架里还没有的名字:{@code base} 被占了就追加 _2/_3…(文件名即身份,不覆盖别人)。 */
    public EntryId unique(String namespace, String base) {
        String candidate = base;
        int i = 2;
        while (get(new EntryId(namespace, candidate)) != null) {
            candidate = base + "_" + i++;
        }
        return new EntryId(namespace, candidate);
    }

    /** 这一条应该在的位置(文件型是文件,目录型是目录),不管在不在。 */
    public Path pathOf(EntryId id) {
        String leaf = kind.isDirectory() ? id.name() : id.name() + kind.suffix();
        return dir().resolve(id.namespace()).resolve(leaf);
    }

    /** 写一条文件型的条目(新建或换掉)。 */
    public synchronized Item write(EntryId id, String text) {
        if (kind.isDirectory()) {
            throw new IllegalStateException(kind.dir() + " 的条目是目录,用 copyIn");
        }
        Path file = pathOf(id);
        Item item = new Item(id, file, text);
        put(item);
        root.io().write(() -> Atomic.write(file, text));
        return item;
    }

    /** 把一个目录整个放成一条目录型的条目(新建或换掉)。 */
    public synchronized Item copyIn(EntryId id, Path source) {
        if (!kind.isDirectory()) {
            throw new IllegalStateException(kind.dir() + " 的条目是文件,用 write");
        }
        Path target = pathOf(id);
        Item item = new Item(id, target, null);
        put(item);
        root.io().write(() -> {
            Trees.delete(target);
            Trees.copy(source, target);
        });
        return item;
    }

    /** 删一条;没有这一条是什么也不做。 */
    public synchronized void delete(EntryId id) {
        Item gone = items.get(id);
        if (gone == null) {
            return;
        }
        Map<EntryId, Item> next = new LinkedHashMap<>(items);
        next.remove(id);
        replace(next);
        root.io().write(() -> {
            if (kind.isDirectory()) {
                Trees.delete(gone.path());
            } else {
                Trees.deleteFile(gone.path());
            }
            Trees.deleteIfEmpty(gone.path().getParent());
        });
    }

    private void put(Item item) {
        Map<EntryId, Item> next = new LinkedHashMap<>(items);
        next.put(item.id(), item);
        replace(next);
    }

    private void replace(Map<EntryId, Item> next) {
        items = next;
        version++;
    }
}
