package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.platform.services.IPlatformHelper;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Paper 上的 {@link IPlatformHelper}。"模组 id"就是插件名:Numen API 与 Numen 的插件名同它们的模组 id
 * ({@code numen_api}、{@code numen}),资源命名空间也是这个名字;第三方插件想带语言文件,插件名取成自己的命名空间。
 */
public final class PaperPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Paper";
    }

    /**
     * 配置根 {@code config/numen/} 在 Paper 上是 {@code plugins/numen/},所以"配置目录"就是 {@code plugins/}。
     * 目录名小写,与 {@link Constants#CONFIG_ROOT} 是同一个答案(Linux 区分大小写),也与插件 {@code numen} 的数据目录重合。
     */
    @Override
    public Path getConfigDir() {
        return Bukkit.getPluginsFolder().toPath();
    }

    @Override
    public boolean isModLoaded(String modId) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(modId);
        return plugin != null && plugin.isEnabled();
    }

    /** Paper 没有开发环境与成品环境之分。 */
    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    /** 带 Numen 内容的插件:Numen API 自己,和声明依赖它的插件。 */
    @Override
    public List<String> modIds() {
        return carriers().stream().map(Plugin::getName).toList();
    }

    @Override
    public Path modResource(String modId, String path) {
        return carriers().stream().filter(p -> p.getName().equals(modId)).findFirst()
                .map(plugin -> PluginJars.find(plugin, path)).orElse(null);
    }

    private static List<Plugin> carriers() {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(Plugin::isEnabled)
                .filter(p -> p.getName().equals(Constants.MOD_ID) || dependsOnNumenApi(p))
                .toList();
    }

    private static boolean dependsOnNumenApi(Plugin plugin) {
        var meta = plugin.getPluginMeta();
        return meta.getPluginDependencies().contains(Constants.MOD_ID)
                || meta.getPluginSoftDependencies().contains(Constants.MOD_ID);
    }
}
