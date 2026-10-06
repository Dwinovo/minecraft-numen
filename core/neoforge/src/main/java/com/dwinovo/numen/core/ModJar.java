package com.dwinovo.numen.core;

import net.neoforged.fml.ModList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 本模组 jar 里的一条路径对应的 {@link Path},给引擎原地读(技能目录那种"整个目录"的东西)。
 *
 * <p>内容根从 FML 的 mod-file 口 {@code IModFile.getContents().getContentRoots} 取,不经类加载器。FML 10 的内容根是
 * 开发运行时各源码集的输出目录、成品里是 jar 文件本身——jar 文件不能直接 resolve 出里面的目录,所以把它挂成 zip
 * 文件系统再 resolve。挂上的文件系统随游戏一直开着:引擎每次扫描都原地读它。
 * core 自己的 {@code skills}、{@code modules} 根和内嵌联动的 {@code plugins/<模块>/skills} 根都从这里取,只此一个出处。
 */
public final class ModJar {

    /** 已挂成文件系统的 jar,按 jar 路径记,同一个 jar 只挂一次。 */
    private static final Map<Path, FileSystem> MOUNTED = new HashMap<>();

    private ModJar() {}

    /**
     * @param inJar jar 内路径,如 {@code skills} 或 {@code plugins/ysm/skills}
     * @return 对应的 Path;jar 里没有这条路径就 null
     */
    public static synchronized Path find(String inJar) {
        for (Path root : ModList.get().getModFileById(Constants.MOD_ID).getFile().getContents().getContentRoots()) {
            Path base = Files.isDirectory(root) ? root : mount(root).getPath("/");
            Path path = base.resolve(inJar);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    private static FileSystem mount(Path jar) {
        return MOUNTED.computeIfAbsent(jar, j -> {
            try {
                return FileSystems.newFileSystem(j);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open mod jar " + j, e);
            }
        });
    }
}
