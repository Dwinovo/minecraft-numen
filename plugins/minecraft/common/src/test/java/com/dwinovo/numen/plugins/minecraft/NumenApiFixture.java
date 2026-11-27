package com.dwinovo.numen.plugins.minecraft;

/**
 * Numen 的单测要用登记处时共用的一步:引导 MC(方块注册表,原语要认方块),再照 {@link NumenContent#init()} 同一份登记装上 Numen 的全部 API
 * 组,和 Numen API 自己的工具与几组。登记处与工具表是进程级的静态表,一个进程只装一次;各测试类都经这里装,不各装各的一份——装两次会撞名。
 */
public final class NumenApiFixture {

    private static boolean installed;

    private NumenApiFixture() {}

    public static synchronized void install() {
        if (installed) {
            return;
        }
        // 单测里没有加载器给的配置目录:花名册的停用名单放进这次测试专用的空目录
        try {
            java.nio.file.Path plugins = java.nio.file.Files.createTempDirectory("numen-test-plugins-");
            plugins.toFile().deleteOnExit();
            com.dwinovo.numen.api.NumenPlugins.useRoster(new com.dwinovo.numen.api.plugin.PluginRoster(
                    () -> new com.dwinovo.numen.api.plugin.PluginSwitches(new com.dwinovo.numen.api.store.JsonDoc(
                            plugins.resolve(com.dwinovo.numen.api.plugin.PluginSwitches.FILE),
                            com.dwinovo.numen.api.store.JsonDoc.Format.of(1),
                            com.dwinovo.numen.api.store.Io.direct(e -> {
                                throw e;
                            }))),
                    id -> com.dwinovo.numen.api.plugin.PluginRoster.Meta.NONE));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        NumenContent.init();
        // 内置内容产品里由加载器从 jar 里的 numen/ 交出去,单测从类路径上同一个目录交
        com.dwinovo.numen.api.NumenPlugins.register(Constants.PLUGIN,
                numen -> numen.bundleContent(resource("numen")));
        // Numen API 自己的工具与几组(跑脚本、装技能、记计划、札记四个工具,api、permission、task 三组)产品里在初始化时登记,单测里同一个
        // 入口登记一次
        com.dwinovo.numen.api.CommonClass.registerTools();
        com.dwinovo.numen.api.CommonClass.registerApi();
        com.dwinovo.numen.api.CommonClass.registerBundledContent(engineBundled());
        installed = true;
    }

    private static java.nio.file.Path resource(String dir) {
        try {
            return java.nio.file.Path.of(NumenApiFixture.class.getClassLoader().getResource(dir).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 引擎自带的 numen/ 目录(类路径上有 numen/modules/numen 的那一个):单测里没有加载器,从类路径取。 */
    private static java.nio.file.Path engineBundled() {
        try {
            var urls = NumenApiFixture.class.getClassLoader().getResources("numen");
            while (urls.hasMoreElements()) {
                java.net.URI uri = urls.nextElement().toURI();
                java.nio.file.Path dir;
                if ("jar".equals(uri.getScheme())) {
                    java.nio.file.FileSystem fs;
                    try {
                        fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of());
                    } catch (java.nio.file.FileSystemAlreadyExistsException e) {
                        fs = java.nio.file.FileSystems.getFileSystem(uri);
                    }
                    dir = fs.getPath("/numen");
                } else {
                    dir = java.nio.file.Path.of(uri);
                }
                if (java.nio.file.Files.isDirectory(dir.resolve("modules").resolve("numen"))) {
                    return dir;
                }
            }
        } catch (java.io.IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("no numen/modules/numen on the class path");
    }
}
