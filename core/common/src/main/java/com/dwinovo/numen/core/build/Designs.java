package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.blueprint.BlueprintStore;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 设计库:服务器蓝图库下的 {@code schematics/designs/<名>.numen},和 {@code .litematic} 同库,跨世界复用。每次都从盘上读
 * ——主人手改过的文件下一次就生效,读不通的如实报出是哪一行({@link Design#parse})。
 *
 * <p>设计和蓝图文件共用一个名字空间:{@code build at house} 只能指一样东西,所以新建设计时撞了蓝图文件的名字就拒。
 */
public final class Designs {

    private static final String EXTENSION = ".numen";

    private Designs() {}

    /** 设计放在哪;不存在就建。 */
    public static Path dir(MinecraftServer server) {
        Path dir = BlueprintStore.dir(server).resolve("designs");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create design directory " + dir, e);
        }
        return dir;
    }

    /** 库里有哪些设计,按名字排序。 */
    public static List<String> names(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        try (var files = Files.list(dir(server))) {
            files.map(p -> p.getFileName().toString())
                    .filter(f -> f.toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                    .map(f -> f.substring(0, f.length() - EXTENSION.length()))
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list design directory", e);
        }
        return names;
    }

    /** 库里一个名字指的是什么。 */
    public enum Kind { DESIGN, BLUEPRINT_FILE }

    /**
     * 这个名字在库里指的是一份设计还是一个蓝图文件——{@code build at} 与 {@code build show} 都按它认。
     *
     * @throws IllegalArgumentException 两样都没有;或两样都叫这个名字(蓝图文件是后放进来的),说不清指哪个
     */
    public static Kind kindOf(MinecraftServer server, String name) {
        boolean design = exists(server, name);
        boolean file = BlueprintStore.list(server).contains(name);
        if (design && file) {
            throw new IllegalArgumentException("both a design and a blueprint file are named " + name
                    + "; rename the file in the schematics folder so the name means one thing");
        }
        if (!design && !file) {
            throw new IllegalArgumentException("there is no design or blueprint file named " + name
                    + "; build designs lists them");
        }
        return design ? Kind.DESIGN : Kind.BLUEPRINT_FILE;
    }

    /** 库里有没有叫这个名字的设计;不合设计名规矩的名字(带空格的蓝图文件名这类)一定没有。 */
    public static boolean exists(MinecraftServer server, String name) {
        return Design.isName(name) && Files.exists(file(server, name));
    }

    /**
     * 读一份设计:读通、画通了才给。
     *
     * @throws IllegalArgumentException 没有这份设计,或它写不通(说清是哪一行)
     */
    public static Design load(MinecraftServer server, String name) {
        Path file = file(server, name);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("there is no design named " + name + "; build designs lists them");
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read design " + name, e);
        }
        try {
            return Design.parse(name, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("design " + name + " does not read: " + e.getMessage(), e);
        }
    }

    /**
     * 存一份设计(覆盖同名的那份):要写的文本先照读的规矩读一遍,读得回来才写,所以盘上的每一份都读得通。先写到旁边再换过去,
     * 写到一半的文件不会顶掉原来那份。
     */
    public static void save(MinecraftServer server, Design design) {
        String text = design.text();
        Design.parse(design.name(), text);
        Path file = file(server, design.name());
        Path next = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(next, text, StandardCharsets.UTF_8);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write design " + design.name(), e);
        }
    }

    public static void delete(MinecraftServer server, String name) {
        try {
            Files.delete(file(server, name));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete design " + name, e);
        }
    }

    private static Path file(MinecraftServer server, String name) {
        return dir(server).resolve(Design.checkedName(name) + EXTENSION);
    }
}
