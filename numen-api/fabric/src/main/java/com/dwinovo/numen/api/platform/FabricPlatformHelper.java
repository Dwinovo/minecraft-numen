package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public java.nio.file.Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public java.util.List<String> modIds() {
        return FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).toList();
    }

    @Override
    public String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    @Override
    public String modName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getName()).orElse(null);
    }

    @Override
    public java.util.List<String> modAuthors(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getAuthors().stream()
                        .map(net.fabricmc.loader.api.metadata.Person::getName).toList())
                .orElse(java.util.List.of());
    }

    @Override
    public java.nio.file.Path modResource(String modId, String path) {
        return FabricLoader.getInstance().getModContainer(modId).flatMap(mod -> mod.findPath(path)).orElse(null);
    }
}
