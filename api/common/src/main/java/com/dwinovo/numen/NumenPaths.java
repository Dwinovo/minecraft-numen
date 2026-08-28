package com.dwinovo.numen;

import com.dwinovo.numen.platform.Services;

import java.nio.file.Path;

/**
 * Numen 的东西落在磁盘上的哪里——这个问题的<b>唯一</b>答案。
 *
 * <p>在这之前引擎里有三种拼法:客户端从 {@code Minecraft.gameDirectory} 往下拼、
 * 公共侧走 {@code Services.PLATFORM}、迁移那条从加载器传进来。三种拼法在开发
 * 环境下会指向不同目录,而"配置在哪"这种事一旦有两个答案,迟早有人读到空的那个
 * ——#66 就是这么来的。
 */
public final class NumenPaths {

    private NumenPaths() {}

    /**
     * {@code config/numen/} ——引擎与插件共用的配置目录。<b>不保证已存在</b>,
     * 写之前自己 {@code createDirectories}。
     *
     * <p>插件的持久数据也放这儿,文件名带上自己的 mod id(如
     * {@code numen_tlm-wardrobe.json})。玩家找 Numen 相关的东西只需要看这一个目录。
     */
    public static Path config() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.CONFIG_ROOT);
    }
}
