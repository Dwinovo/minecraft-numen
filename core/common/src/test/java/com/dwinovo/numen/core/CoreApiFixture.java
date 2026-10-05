package com.dwinovo.numen.core;

/**
 * core 的单测要用登记处时共用的一步:引导 MC(方块注册表,原语要认方块),再照 {@link NumenCore#init()} 同一份登记装上 core 的全部 API
 * 组,和引擎自己的工具与几组。登记处与工具表是进程级的静态表,一个进程只装一次;各测试类都经这里装,不各装各的一份——装两次会撞名。
 */
public final class CoreApiFixture {

    private static boolean installed;

    private CoreApiFixture() {}

    public static synchronized void install() {
        if (installed) {
            return;
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        NumenCore.init();
        // 内置的 Lua 模块产品里由加载器从 jar 里的 modules/ 交出去,单测从类路径上同一个目录交
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN,
                numen -> numen.bundleModules(resource("modules")));
        // 她的 Lua 模块落在这次测试专用的空目录里,和评测、GameTest 一样只有内置那一层
        try {
            java.nio.file.Path modules = java.nio.file.Files.createTempDirectory("numen-test-lua-");
            modules.toFile().deleteOnExit();
            com.dwinovo.numen.script.Modules.init(uuid -> modules);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        // 引擎自己的工具与几组(跑脚本、装技能、记计划、札记四个工具,api、mc、task、module 四组)产品里在初始化时登记,单测里同一个
        // 入口登记一次
        com.dwinovo.numen.CommonClass.registerTools();
        com.dwinovo.numen.CommonClass.registerApi();
        installed = true;
    }

    private static java.nio.file.Path resource(String dir) {
        try {
            return java.nio.file.Path.of(CoreApiFixture.class.getClassLoader().getResource(dir).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
