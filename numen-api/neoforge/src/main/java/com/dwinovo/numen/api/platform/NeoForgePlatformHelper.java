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

        return !FMLLoader.isProduction();
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

    @Override
    public java.nio.file.Path modResource(String modId, String path) {
        var file = ModList.get().getModFileById(modId);
        if (file == null) {
            return null;
        }
        // findResource 对不存在的路径也给一个 Path,存在与否在这里判
        java.nio.file.Path found = file.getFile().findResource(path);
        return java.nio.file.Files.exists(found) ? found : null;
    }
}
