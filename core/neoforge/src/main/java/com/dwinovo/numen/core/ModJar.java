package com.dwinovo.numen.core;

import net.neoforged.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本模组 jar 里的一条路径对应的 {@link Path},给引擎原地读(技能目录那种"整个目录"的东西)。
 *
 * <p>jar 内路径怎么映射成 Path 只有加载器知道(开发运行是磁盘目录,成品是挂在 union
 * 文件系统上的 jar),所以走 FML 的 mod-file 口 {@code IModFile.getContents().getContentRoots()},
 * 在各内容根下找这条路径,不经类加载器。
 * core 自己的 {@code skills} 根和内嵌联动的 {@code plugins/<模块>/skills} 根都从这里取,
 * 只此一个出处。
 */
public final class ModJar {

    private ModJar() {}

    /**
     * @param inJar jar 内路径,如 {@code skills} 或 {@code plugins/ysm/skills}
     * @return 对应的 Path;所有内容根下都没有这条路径就 null(开发运行时类与资源分在几个根里,逐个找)
     */
    public static Path find(String inJar) {
        for (Path root : ModList.get().getModFileById(Constants.MOD_ID).getFile().getContents().getContentRoots()) {
            Path path = root.resolve(inJar);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }
}
