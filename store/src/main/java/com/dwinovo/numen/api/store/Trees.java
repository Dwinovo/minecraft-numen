package com.dwinovo.numen.api.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** 整个目录树的复制与删除:库里的目录型条目、旧数据的迁移、家的删除都走这一份。失败抛 {@link StoreException}。 */
public final class Trees {

    private Trees() {}

    /** 把 {@code from} 整个复制到 {@code to}(已有的同名文件被换掉)。 */
    public static void copy(Path from, Path to) {
        copy(from, to, name -> true);
    }

    /** 同上,但 {@code from} 下第一层只复制 {@code topLevel} 认可名字的那些。 */
    public static void copy(Path from, Path to, Predicate<String> topLevel) {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path relative = from.relativize(source);
                if (relative.getNameCount() > 0 && !topLevel.test(relative.getName(0).toString())) {
                    continue;
                }
                Path target = to.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new StoreException("复制不了 " + from + ": " + e, e);
        }
    }

    /** 整个目录删掉;本来就不在是什么也不做。 */
    public static void delete(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new StoreException("删不掉 " + dir + ": " + e, e);
        }
    }

    /** 删一个文件;本来就不在是什么也不做。 */
    public static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StoreException("删不掉 " + file + ": " + e, e);
        }
    }

    /** 从 {@code from} 起往上,空目录一层层删掉,到 {@code stop}(不含)或遇到不空的为止。 */
    public static void deleteEmptyParents(Path from, Path stop) {
        Path dir = from;
        while (dir != null && !dir.equals(stop) && dir.startsWith(stop) && Files.isDirectory(dir)) {
            deleteIfEmpty(dir);
            if (Files.exists(dir)) {
                return;
            }
            dir = dir.getParent();
        }
    }

    /** 目录是空的就删掉(一层);不空或不在是什么也不做。 */
    public static void deleteIfEmpty(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isPresent()) {
                return;
            }
            Files.delete(dir);
        } catch (IOException e) {
            throw new StoreException("删不掉 " + dir + ": " + e, e);
        }
    }
}
