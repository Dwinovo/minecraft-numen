package com.dwinovo.numen.core;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * 本模组 jar 里的一条路径对应的 {@link Path},给引擎原地读(技能目录那种"整个目录"的东西)。
 *
 * <p>jar 内路径怎么映射成 Path 只有加载器知道(开发运行是磁盘目录,成品是挂成文件系统的
 * jar),所以走 Fabric 的 mod-file 口 {@code ModContainer.findPath},不经类加载器。
 * core 自己的 {@code skills} 根和内嵌联动的 {@code plugins/<模块>/skills} 根都从这里取,
 * 只此一个出处。
 */
public final class ModJar {

    private ModJar() {}

    /**
     * @param inJar jar 内路径,如 {@code skills} 或 {@code plugins/ysm/skills}
     * @return 对应的 Path;jar 里没有这条路径就 null
     */
    public static Path find(String inJar) {
        return FabricLoader.getInstance().getModContainer(Constants.MOD_ID).orElseThrow()
                .findPath(inJar)
                .orElse(null);
    }
}
