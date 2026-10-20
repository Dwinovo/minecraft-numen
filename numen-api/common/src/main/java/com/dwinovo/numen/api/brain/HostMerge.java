package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.store.StoreException;
import com.dwinovo.numen.api.store.Trees;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 房主的"我的"就是服务器目录那一份(见 {@code Library#merged}),所以他原来个人目录({@code players/<房主>/})里的各库条目与钥匙,
 * 并进服务器目录({@code server/})。规矩同 {@link LegacyMigration}:<b>可重来</b>——一条一条整个移过去(同一个文件系统内的改名),
 * 移完个人目录里就没有它了,下次启动没有什么可搬的;崩在半路,没搬的下次接着搬。
 *
 * <ul>
 *   <li>条目:目标里没有同名的就移过去;同名而且内容一模一样(多半是随 jar 发的默认内容)就丢掉他这份;内容不同则按"我的优先"——
 *       他原来读到的是他这份,所以他这份改名({@code 名字_2}……)移过去,返回的 {@link Renamed} 说明改成了什么,
 *       他的同伴绑着的旧名字(档案、人设、声线)由 {@link #rebind} 改指新的,界面再告诉他;</li>
 *   <li>钥匙:同一个服务两边都有而且不同,他的留下(用钥匙时他的本来就排在服主的前面),服主的被它换掉,返回的服务名界面告诉他。</li>
 * </ul>
 */
public final class HostMerge {

    /** 一条因同名而改了名的条目。 */
    public record Renamed(LibraryKind kind, EntryId from, EntryId to) {}

    private HostMerge() {}

    /**
     * 房主个人目录里这些库的条目并进服务器目录。
     *
     * @param kinds 要并的库;"我的"永远是玩家自己那一份的库({@link LibraryKind#ownerOnly})不在其中
     * @return 因同名改了名的
     */
    public static List<Renamed> libraries(Path hostDir, Path serverDir, List<LibraryKind> kinds) {
        List<Renamed> renamed = new ArrayList<>();
        for (LibraryKind kind : kinds) {
            Path from = hostDir.resolve(kind.dir());
            if (!Files.isDirectory(from)) {
                continue;
            }
            for (Path space : children(from)) {
                String namespace = space.getFileName().toString();
                if (!Files.isDirectory(space) || !EntryId.segment(namespace)) {
                    continue;
                }
                for (Path entry : children(space)) {
                    EntryId id = idOf(kind, namespace, entry);
                    if (id != null) {
                        move(kind, id, entry, serverDir.resolve(kind.dir()).resolve(namespace), renamed);
                    }
                }
                Trees.deleteIfEmpty(space);
            }
            Trees.deleteIfEmpty(from);
        }
        return renamed;
    }

    /**
     * 房主个人钥匙串里的钥匙并进服主的。
     *
     * @return 把服主的同服务钥匙换掉了的那些服务
     */
    public static List<String> keys(Path hostKeys, Path serverKeys) {
        List<String> replaced = new ArrayList<>();
        if (!Files.isDirectory(hostKeys)) {
            return replaced;
        }
        for (Path key : children(hostKeys)) {
            String name = key.getFileName().toString();
            if (!Files.isRegularFile(key) || !name.endsWith(".key")) {
                continue;
            }
            Path target = serverKeys.resolve(name);
            if (Files.isRegularFile(target)) {
                if (Arrays.equals(read(key), read(target))) {
                    Trees.deleteFile(key);
                    continue;
                }
                replaced.add(name.substring(0, name.length() - ".key".length()));
            }
            place(key, target);
        }
        Trees.deleteIfEmpty(hostKeys);
        return replaced;
    }

    /**
     * 她们的绑定里记着的旧名字改指新的(模型档案、人设、声线)。绑定文件可能不存在。
     *
     * @return 改了几个文件
     */
    public static int rebind(List<Path> bindingFiles, List<Renamed> renamed) {
        int changed = 0;
        for (Path file : bindingFiles) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            JsonDoc doc = new JsonDoc(file, JsonDoc.Format.of(1), Io.direct(e -> {
                throw e;
            }));
            JsonObject binding = doc.read().orElse(new JsonObject());
            boolean edited = false;
            for (Renamed each : renamed) {
                for (Slot slot : Slot.values()) {
                    if (slot.kind().equals(each.kind()) && binding.get(slot.wire()) instanceof JsonPrimitive p
                            && p.getAsString().equals(each.from().toString())) {
                        binding.addProperty(slot.wire(), each.to().toString());
                        edited = true;
                    }
                }
            }
            if (edited) {
                doc.write(binding);
                changed++;
            }
        }
        return changed;
    }

    // ---- 搬一条 ----

    private static void move(LibraryKind kind, EntryId id, Path source, Path targetSpace, List<Renamed> renamed) {
        Path target = targetSpace.resolve(leaf(kind, id.name()));
        if (Files.exists(target)) {
            if (same(source, target)) {
                delete(source);
                return;
            }
            EntryId fresh = unique(kind, id, targetSpace);
            renamed.add(new Renamed(kind, id, fresh));
            Constants.LOG.info("[numen-migrate] 房主的 {} {} 和服务器目录里的同名,改名 {} 并入", kind.dir(), id, fresh);
            target = targetSpace.resolve(leaf(kind, fresh.name()));
        }
        place(source, target);
    }

    private static EntryId unique(LibraryKind kind, EntryId id, Path targetSpace) {
        String name = id.name();
        for (int i = 2; Files.exists(targetSpace.resolve(leaf(kind, name))); i++) {
            name = id.name() + "_" + i;
        }
        return new EntryId(id.namespace(), name);
    }

    /** 一条在它那一处的文件名(文件型带后缀,目录型就是名字)。 */
    private static String leaf(LibraryKind kind, String name) {
        return kind.isDirectory() ? name : name + kind.suffix();
    }

    /** 这个路径是不是这种库的一条;不是(杂物、{@code .new})是 {@code null}。 */
    private static EntryId idOf(LibraryKind kind, String namespace, Path entry) {
        String file = entry.getFileName().toString();
        if (kind.isDirectory()) {
            return Files.isDirectory(entry) && EntryId.segment(file) && Files.isRegularFile(entry.resolve(kind.marker()))
                    ? new EntryId(namespace, file) : null;
        }
        if (!Files.isRegularFile(entry) || !file.endsWith(kind.suffix())) {
            return null;
        }
        String name = file.substring(0, file.length() - kind.suffix().length());
        return EntryId.segment(name) ? new EntryId(namespace, name) : null;
    }

    private static void place(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StoreException("搬不动 " + source + ": " + e, e);
        }
    }

    // ---- 比较 ----

    private static boolean same(Path a, Path b) {
        if (Files.isRegularFile(a) && Files.isRegularFile(b)) {
            return Arrays.equals(read(a), read(b));
        }
        if (!Files.isDirectory(a) || !Files.isDirectory(b)) {
            return false;
        }
        List<Path> left = files(a);
        if (left.size() != files(b).size()) {
            return false;
        }
        for (Path file : left) {
            Path other = b.resolve(a.relativize(file).toString());
            if (!Files.isRegularFile(other) || !Arrays.equals(read(file), read(other))) {
                return false;
            }
        }
        return true;
    }

    private static List<Path> files(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new StoreException("读不了 " + dir + ": " + e, e);
        }
    }

    private static List<Path> children(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.sorted().toList();
        } catch (IOException e) {
            throw new StoreException("读不了 " + dir + ": " + e, e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new StoreException("读不了 " + file + ": " + e, e);
        }
    }

    private static void delete(Path entry) {
        if (Files.isDirectory(entry)) {
            Trees.delete(entry);
        } else {
            Trees.deleteFile(entry);
        }
    }
}
