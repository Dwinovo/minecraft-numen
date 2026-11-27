package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.CommonClass;
import com.dwinovo.numen.api.script.Modules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SDK 单测共用的一步:Numen API 自己的几组(帮助、原版指令、手上的活、模块)产品里在初始化时登记,单测里同一个入口登记一次;她的 Lua 模块落在
 * 这次测试专用的空目录里,只有出厂那一层。登记处是进程级的静态表,各测试类登记各自名字的组,互不相撞。
 */
public final class SdkFixture {

    /** 测试组登记进的名字空间。 */
    static final String NAMESPACE = "gt";

    static {
        com.dwinovo.numen.api.plugin.PluginRosterFixture.install();
        if (ApiRegistry.group("numen.permission") == null) {
            CommonClass.registerApi();
            CommonClass.registerBundledContent(bundled());
        }
    }

    private SdkFixture() {}

    /** 只为让上面的初始化跑一遍(名字空间是编译期常量,单读它不触发类初始化)。 */
    public static void ready() {}

    /** 登记一组测试函数(名字空间 {@link #NAMESPACE})。 */
    public static void register(String group, Class<?> functions) {
        ApiRegistry.register(NAMESPACE, group, "Test fixture.", functions);
    }
    /** 引擎自带的 numen/ 目录(类路径上有 numen/modules/numen 的那一个):单测里没有加载器,从类路径取。 */
    private static java.nio.file.Path bundled() {
        try {
            var urls = SdkFixture.class.getClassLoader().getResources("numen");
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
