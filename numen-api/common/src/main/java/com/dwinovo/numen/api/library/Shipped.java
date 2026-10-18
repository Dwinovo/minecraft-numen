package com.dwinovo.numen.api.library;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.store.Atomic;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.store.StoreException;
import com.dwinovo.numen.api.store.Trees;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * jar 只负责把内置放进去。Numen 与每个插件各自在自己的 jar 里带一个 {@code numen/} 目录(里面是 {@code <库>/<命名空间>/<名字>}),
 * 经 {@code NumenApi.bundleContent} 交上来({@link #bundle});服务器起来时 {@link #sync} 把它们同步进
 * {@code config/numen/server/},此后读的只有那个目录。
 *
 * <p>同步照 dpkg 对配置文件的办法,清单 {@code .shipped.json} 记每个文件的来源与上次写进去的指纹:
 * <ul>
 *   <li>文件没被动过(现在的指纹等于清单里的):换成这一版的;</li>
 *   <li>被管理员改过:留他的,这一版写在旁边的 {@code <文件>.new},界面提示"默认内容有更新";</li>
 *   <li>管理员删掉的:清单记下,不再放回来;</li>
 *   <li>来源卸载了(或这一版不再带它):没被改过的一起删,改过的留下,标"来源已卸载"。</li>
 * </ul>
 * 清单是文件级的:目录型的条目(技能)里每个文件各按各的算。
 */
public final class Shipped {

    /** 清单的文件名,在服务器目录下。 */
    public static final String MANIFEST = ".shipped.json";
    /** 管理员改过、jar 又有新版时,新版写在旁边的后缀。 */
    public static final String NEW_SUFFIX = ".new";

    /** 一个来源带来的内容。 */
    public record Bundle(String source, Path root) {}

    private static final List<Bundle> BUNDLES = new CopyOnWriteArrayList<>();

    private Shipped() {}

    /**
     * 登记一个来源的 {@code numen/} 目录。同一个文件只能有一个来源:另一个来源已经带了同一条路径,是破坏系统的硬错误。
     * 同一个来源重复交同一个目录是一回事,什么也不做。
     *
     * @param source 来源的名字空间(Numen 是 {@code numen},插件是它自己的 id)
     * @return 这一次新登记了;这个来源的这个目录早就登记过是 {@code false}
     * @throws IllegalArgumentException 同一条路径被两个来源带
     */
    public static synchronized boolean bundle(String source, Path root) {
        Bundle candidate = new Bundle(source, root);
        if (BUNDLES.contains(candidate)) {
            return false;
        }
        requireDistinct(candidate, BUNDLES);
        BUNDLES.add(candidate);
        return true;
    }

    /** 同一条路径只许一个来源带:{@code candidate} 带的文件里有已登记的来源也带了的,抛出。 */
    static void requireDistinct(Bundle candidate, List<Bundle> registered) {
        Map<String, Path> mine = files(candidate.root());
        for (Bundle other : registered) {
            for (String path : files(other.root()).keySet()) {
                if (mine.containsKey(path)) {
                    throw new IllegalArgumentException("内置内容 " + path + " 被 " + other.source() + " 和 "
                            + candidate.source() + " 都带了——同一个文件只能有一个来源");
                }
            }
        }
    }

    /** 到目前为止登记的来源。 */
    public static List<Bundle> bundles() {
        return List.copyOf(BUNDLES);
    }

    // ==================== 同步 ====================

    /**
     * 把登记过的内容同步进服务器目录。服务器起来时调一次;之后读的只有服务器目录。
     *
     * @return 同步后的清单(界面问"改过没有、有没有新版"问它)
     */
    public static Manifest sync(Path serverRoot) {
        return sync(serverRoot, bundles());
    }

    /** 同上,来源由调用方给(单测)。 */
    public static Manifest sync(Path serverRoot, List<Bundle> bundles) {
        Manifest manifest = Manifest.open(serverRoot);
        Map<String, Shipment> shipped = new TreeMap<>();
        for (Bundle bundle : bundles) {
            files(bundle.root()).forEach((path, file) -> shipped.put(path, new Shipment(bundle.source(), file)));
        }
        manifest.jar = shipped;
        shipped.forEach((path, shipment) -> manifest.place(path, shipment));
        for (String path : List.copyOf(manifest.files.keySet())) {
            if (!shipped.containsKey(path)) {
                manifest.retire(path);
            }
        }
        manifest.save();
        return manifest;
    }

    /** jar 里的一个文件和它的来源。 */
    private record Shipment(String source, Path file) {}

    /** 一个目录下的全部文件:相对路径(用 {@code /})到文件。 */
    private static Map<String, Path> files(Path root) {
        Map<String, Path> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                out.put(slashed(root.relativize(file)), file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读不了内置内容 " + root, e);
        }
        return out;
    }

    private static String slashed(Path relative) {
        List<String> parts = new ArrayList<>();
        relative.forEach(part -> parts.add(part.toString()));
        return String.join("/", parts);
    }

    private static String fingerprint(Path file) {
        try {
            return Fingerprint.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("读不了 " + file, e);
        }
    }

    // ==================== 清单 ====================

    /** 清单里一个文件的记录。 */
    private static final class Tracked {
        String source;
        /** 上次同步写进去的指纹;空串 = 管理员已有的同名文件占了位置,没有哪一版是它的底。 */
        String fingerprint;
        /** 管理员删掉了它。 */
        boolean deleted;
        /** 来源不再带它,但管理员改过所以留着。 */
        boolean gone;
    }

    /**
     * 同步清单,同时是内置条目"改过没有、有没有新版"的出处({@link Provenance.Source})。
     */
    public static final class Manifest implements Provenance.Source {

        private static final JsonDoc.Format FORMAT = JsonDoc.Format.of(1);

        private final Path serverRoot;
        private final JsonDoc doc;
        private final Map<String, Tracked> files = new LinkedHashMap<>();
        /** 这一次同步时 jar 里带的每个文件("恢复默认"要取回的就是它);没同步过是空的。 */
        private Map<String, Shipment> jar = Map.of();

        private Manifest(Path serverRoot) {
            this.serverRoot = serverRoot;
            this.doc = new JsonDoc(serverRoot.resolve(MANIFEST), FORMAT, Io.direct(e -> {
                throw e;
            }));
        }

        /** 读服务器目录下的清单;没有是空的。读不通是 {@link StoreException}——清单对不上就不知道哪些文件被动过,不能当没有。 */
        public static Manifest open(Path serverRoot) {
            Manifest manifest = new Manifest(serverRoot);
            manifest.doc.read().ifPresent(root -> {
                JsonObject entries = root.has("files") ? root.getAsJsonObject("files") : new JsonObject();
                entries.entrySet().forEach(e -> {
                    JsonObject o = e.getValue().getAsJsonObject();
                    Tracked record = new Tracked();
                    record.source = o.get("source").getAsString();
                    record.fingerprint = o.get("fingerprint").getAsString();
                    record.deleted = o.has("deleted") && o.get("deleted").getAsBoolean();
                    record.gone = o.has("gone") && o.get("gone").getAsBoolean();
                    manifest.files.put(e.getKey(), record);
                });
            });
            return manifest;
        }

        void save() {
            JsonObject entries = new JsonObject();
            files.forEach((path, record) -> {
                JsonObject o = new JsonObject();
                o.addProperty("source", record.source);
                o.addProperty("fingerprint", record.fingerprint);
                if (record.deleted) {
                    o.addProperty("deleted", true);
                }
                if (record.gone) {
                    o.addProperty("gone", true);
                }
                entries.add(path, o);
            });
            JsonObject root = new JsonObject();
            root.add("files", entries);
            doc.write(root);
        }

        /** 这一版带来的一个文件,按上面的规则放进服务器目录。 */
        private void place(String path, Shipment shipment) {
            Path target = serverRoot.resolve(path);
            String now = fingerprint(shipment.file());
            Tracked record = files.get(path);
            String onDisk = Files.isRegularFile(target) ? fingerprint(target) : null;
            if (record == null) {
                record = new Tracked();
                record.source = shipment.source();
                files.put(path, record);
                if (onDisk == null) {
                    write(shipment.file(), target);
                    record.fingerprint = now;
                } else if (onDisk.equals(now)) {
                    record.fingerprint = now;
                } else {
                    // 管理员已有的同名文件:当作改过的,没有哪一版是它的底
                    record.fingerprint = "";
                    write(shipment.file(), pending(target));
                }
                return;
            }
            record.source = shipment.source();
            record.gone = false;
            if (onDisk == null) {
                // 被管理员删掉了(这一次或更早):记下,不再放回来
                record.deleted = true;
                Trees.deleteFile(pending(target));
                return;
            }
            record.deleted = false;
            if (onDisk.equals(record.fingerprint)) {
                if (!now.equals(record.fingerprint)) {
                    write(shipment.file(), target);
                    record.fingerprint = now;
                }
                Trees.deleteFile(pending(target));
            } else if (onDisk.equals(now)) {
                record.fingerprint = now;
                Trees.deleteFile(pending(target));
            } else if (!now.equals(record.fingerprint)) {
                write(shipment.file(), pending(target));
            } else {
                Trees.deleteFile(pending(target));
            }
        }

        /** 清单里有、这一版不再带的文件:没改过的删掉,改过的留下标"来源已卸载"。 */
        private void retire(String path) {
            Tracked record = files.get(path);
            Path target = serverRoot.resolve(path);
            boolean present = Files.isRegularFile(target);
            Trees.deleteFile(pending(target));
            if (!present) {
                files.remove(path);
            } else if (fingerprint(target).equals(record.fingerprint)) {
                Trees.deleteFile(target);
                Trees.deleteEmptyParents(target.getParent(), serverRoot);
                files.remove(path);
            } else {
                record.gone = true;
            }
        }

        /** {@code path} 是不是这一条的文件:文件型的条目是那一个文件,目录型的是目录里的每一个。 */
        private static boolean belongs(LibraryKind kind, String base, String path) {
            return kind.isDirectory() ? path.startsWith(base + "/") : path.equals(base + kind.suffix());
        }

        private static String baseOf(LibraryKind kind, EntryId id) {
            return kind.dir() + "/" + id.namespace() + "/" + id.name();
        }

        private static Path pending(Path target) {
            return target.resolveSibling(target.getFileName() + NEW_SUFFIX);
        }

        private static void write(Path from, Path to) {
            try {
                Atomic.write(to, Files.readAllBytes(from));
            } catch (IOException e) {
                throw new UncheckedIOException("读不了 " + from, e);
            }
        }

        // ---- 来历 ----

        @Override
        public Provenance of(LibraryKind kind, Shelf.Item item) {
            String base = baseOf(kind, item.id());
            boolean modified = false;
            boolean update = false;
            boolean gone = false;
            for (Map.Entry<String, Tracked> e : files.entrySet()) {
                String path = e.getKey();
                if (!belongs(kind, base, path)) {
                    continue;
                }
                Path target = serverRoot.resolve(path);
                modified |= Files.isRegularFile(target) && !fingerprint(target).equals(e.getValue().fingerprint);
                update |= Files.isRegularFile(pending(target));
                gone |= e.getValue().gone;
            }
            return new Provenance(modified, update, gone);
        }

        /** 采用新版:{@code <文件>.new} 换掉现在的文件,清单跟上。目录型的条目里每个有新版的文件都换。 */
        public void accept(LibraryKind kind, EntryId id) {
            resolve(kind, id, true);
        }

        /**
         * 恢复默认:管理员改过的内置条目,丢掉他的改动,取回 jar 里这一版——条目的每个文件换回 jar 版,{@code .new} 删掉,清单记成
         * 这一版是它的底(之后没人动它就是没改过)。
         *
         * @return jar 里有这一条;来源已经卸载的没有默认可恢复,是 {@code false}
         */
        public boolean restore(LibraryKind kind, EntryId id) {
            String base = baseOf(kind, id);
            boolean found = false;
            for (Map.Entry<String, Shipment> e : jar.entrySet()) {
                if (!belongs(kind, base, e.getKey())) {
                    continue;
                }
                Path target = serverRoot.resolve(e.getKey());
                write(e.getValue().file(), target);
                Trees.deleteFile(pending(target));
                Tracked record = files.computeIfAbsent(e.getKey(), k -> new Tracked());
                record.source = e.getValue().source();
                record.fingerprint = fingerprint(e.getValue().file());
                record.deleted = false;
                record.gone = false;
                found = true;
            }
            if (found) {
                save();
            }
            return found;
        }

        /** 不采用新版:删掉 {@code <文件>.new},现在的文件保持被改过的样子,等下一版再提示。 */
        public void dismiss(LibraryKind kind, EntryId id) {
            resolve(kind, id, false);
        }

        private void resolve(LibraryKind kind, EntryId id, boolean take) {
            String base = baseOf(kind, id);
            for (Map.Entry<String, Tracked> e : files.entrySet()) {
                String path = e.getKey();
                Path target = serverRoot.resolve(path);
                if (!belongs(kind, base, path) || !Files.isRegularFile(pending(target))) {
                    continue;
                }
                // 不采用也把底记成这一版:这一版不再提示,等下一版
                e.getValue().fingerprint = fingerprint(pending(target));
                if (take) {
                    write(pending(target), target);
                }
                Trees.deleteFile(pending(target));
            }
            save();
        }
    }
}
