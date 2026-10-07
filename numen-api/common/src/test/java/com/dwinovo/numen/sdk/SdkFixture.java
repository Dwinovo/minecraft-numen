package com.dwinovo.numen.sdk;

import com.dwinovo.numen.CommonClass;
import com.dwinovo.numen.script.Modules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SDK 单测共用的一步:引擎自己的几组(帮助、原版指令、手上的活、模块)产品里在初始化时登记,单测里同一个入口登记一次;她的 Lua 模块落在
 * 这次测试专用的空目录里,只有出厂那一层。登记处是进程级的静态表,各测试类登记各自名字的组,互不相撞。
 */
public final class SdkFixture {

    /** 测试组登记进的名字空间。 */
    static final String NAMESPACE = "gt";

    static {
        if (ApiRegistry.group("numen.api") == null) {
            CommonClass.registerApi();
        }
        try {
            Path modules = Files.createTempDirectory("numen-test-lua-");
            modules.toFile().deleteOnExit();
            Modules.init(uuid -> modules);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SdkFixture() {}

    /** 登记一组测试函数(名字空间 {@link #NAMESPACE})。 */
    public static void register(String group, Class<?> functions) {
        ApiRegistry.register(NAMESPACE, group, "Test fixture.", functions);
    }
}
