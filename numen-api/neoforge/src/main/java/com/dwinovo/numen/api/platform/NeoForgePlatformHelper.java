package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {

        return "NeoForge";
    }

    @Override
    public java.nio.file.Path getConfigDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.getCurrent().isProduction();
    }

    @Override
    public java.util.List<String> modIds() {
        return ModList.get().getMods().stream().map(net.neoforged.neoforgespi.language.IModInfo::getModId).toList();
    }

    @Override
    public String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse(null);
    }

    @Override
    public String modName(String modId) {
        return ModList.get().getModContainerById(modId).map(mod -> mod.getModInfo().getDisplayName()).orElse(null);
    }

    /** neoforge.mods.toml 里 {@code authors} 是一段自由文本("A, B"),按逗号拆开。 */
    @Override
    public java.util.List<String> modAuthors(String modId) {
        return ModList.get().getModContainerById(modId)
                .flatMap(mod -> mod.getModInfo().getConfig().<String>getConfigElement("authors"))
                .map(text -> java.util.Arrays.stream(text.split(",")).map(String::strip).filter(a -> !a.isEmpty()).toList())
                .orElse(java.util.List.of());
    }

    /**
     * 内容根从 FML 的 mod-file 口 {@code IModFile.getContents().getContentRoots} 取。FML 10 的内容根是开发运行时各源码集的
     * 输出目录、成品里是 jar 文件本身——jar 文件不能直接 resolve 出里面的路径,所以把它挂成 zip 文件系统再 resolve。
     * 挂上的文件系统随游戏一直开着(读的一方原地读),同一个 jar 只挂一次。
     */
    @Override
    public synchronized java.nio.file.Path modResource(String modId, String path) {
        var file = ModList.get().getModFileById(modId);
        if (file == null) {
            return null;
        }
        for (java.nio.file.Path root : file.getFile().getContents().getContentRoots()) {
            java.nio.file.Path base = java.nio.file.Files.isDirectory(root) ? root : mount(root).getPath("/");
            java.nio.file.Path found = base.resolve(path);
            if (java.nio.file.Files.exists(found)) {
                return found;
            }
        }
        return null;
    }

    /** 已挂成文件系统的 jar,按 jar 路径记。 */
    private final java.util.Map<java.nio.file.Path, java.nio.file.FileSystem> mounted = new java.util.HashMap<>();

    private java.nio.file.FileSystem mount(java.nio.file.Path jar) {
        return mounted.computeIfAbsent(jar, j -> {
            try {
                return java.nio.file.FileSystems.newFileSystem(j);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("cannot open mod jar " + j, e);
            }
        });
    }
}
