package com.dwinovo.numen.api.platform.services;

public interface IPlatformHelper {

    String getPlatformName();

    /** The game's config directory ({@code <gameDir>/config}); used for the user-editable models file. */
    java.nio.file.Path getConfigDir();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** 已加载的全部模组的 id。 */
    java.util.List<String> modIds();

    /**
     * 模组 {@code modId} 的 jar(开发期是它的目录)里 {@code path} 处的文件;没有这个模组或没有这个文件是 {@code null}。
     * jar 内路径怎么映射成 {@link java.nio.file.Path} 只有加载器知道。
     */
    java.nio.file.Path modResource(String modId, String path);

    default String getEnvironmentName() {

        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
