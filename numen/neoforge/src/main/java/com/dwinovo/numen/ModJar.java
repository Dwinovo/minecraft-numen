package com.dwinovo.numen;

import com.dwinovo.numen.api.platform.Services;

import java.nio.file.Path;

/**
 * 本模组 jar 里的一条路径对应的 {@link Path},给 Numen API 同步(内置内容那种"整个目录"的东西)。
 *
 * <p>jar 内路径怎么映射成 Path 只有加载器知道,Numen API 的平台口 {@code modResource} 已经答了这一问
 * (FML 10 的内容根、成品 jar 挂成 zip 文件系统都在那一处),这里不另写一份。
 * Numen 自己的 {@code numen} 目录和内嵌联动的 {@code plugins/<模块>/numen} 目录都从这里取,只此一个出处。
 */
public final class ModJar {

    private ModJar() {}

    /**
     * @param inJar jar 内路径,如 {@code numen} 或 {@code plugins/litematica/numen}
     * @return 对应的 Path;jar 里没有这条路径就 null
     */
    public static Path find(String inJar) {
        return Services.platform().modResource(Constants.MOD_ID, inJar);
    }
}
