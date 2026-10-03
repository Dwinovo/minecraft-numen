package com.dwinovo.numen.core;

/**
 * core 的单测要用登记处时共用的一步:引导 MC(方块注册表,原语要认方块),再照 {@link NumenCore#init()} 同一份登记装上
 * core 的全部命令组,和引擎自己的工具与几组。命令树与工具表是进程级的静态表,一个进程只装一次;各测试类都经这里装,不各装各的一份——
 * 装两次会撞名。
 */
public final class CoreCommandsFixture {

    private static boolean installed;

    private CoreCommandsFixture() {}

    public static synchronized void install() {
        if (installed) {
            return;
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        NumenCore.init();
        // 内置的库与脚本产品里由加载器从 jar 里的 library/、scripts/ 交出去,单测从类路径上同样的两个目录交
        com.dwinovo.numen.api.NumenPlugins.register(numen -> {
            numen.bundleLibrary(resource("library"));
            numen.bundleScripts(resource("scripts"));
        });
        // 引擎自己的工具与几组(跑脚本的工具、api、mc、todo)产品里在客户端初始化时登记,单测里同一个入口登记一次
        com.dwinovo.numen.CommonClass.registerTools();
        installed = true;
    }

    private static java.nio.file.Path resource(String dir) {
        try {
            return java.nio.file.Path.of(CoreCommandsFixture.class.getClassLoader().getResource(dir).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
