package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.script.Modules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 这一次 GameTest 运行专用的库(内置从 jar 同步下来,她的模块与技能落在这里):只用内置原版,不读主人目录里的。
 *
 * <p>服务器起好时产品把模块与技能的落点指到了主人的目录(名册上有的同伴才有主人),用例里的同伴没有登记:落点拿回来。
 * 钩子由 {@link GameTestSuites#all()} 装,那是各加载器在服务器起来之前登记用例的入口,每次运行走一遍,不靠哪个夹具类
 * 什么时候被加载。钩子排在所有开工之后(见 {@link ServerLifecycle#afterStarted}),这时各模组也都登记过内置内容了,
 * 内置才同步得全。
 */
public final class TestLibraries {

    private static LibraryHub libraries;
    private static final UUID OWNER = UUID.randomUUID();
    /** 夹具模块(名字到正文),建库时放进内置组;用例类的静态块登记它们,那时库不能建。 */
    private static final Map<String, String> FIXTURE_MODULES = new LinkedHashMap<>();

    private TestLibraries() {}

    /** 登记一个内置模块(夹具),建库时和 jar 同步下来的内置模块放在同一处。 */
    public static synchronized void builtinModule(String name, String code) {
        FIXTURE_MODULES.put(name, code);
    }

    static void install() {
        ServerLifecycle.afterStarted(server -> {
            Modules.init(uuid -> libraries().library(Modules.KIND, OWNER));
            SkillRegistry.init(uuid -> libraries().library(SkillRegistry.KIND, OWNER));
        });
    }

    private static synchronized LibraryHub libraries() {
        if (libraries == null) {
            try {
                libraries = LibraryHub.headless(Files.createTempDirectory("numen-gametest-lib-"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            FIXTURE_MODULES.forEach((name, code) -> {
                int dot = name.indexOf('.');
                libraries.server(Modules.KIND).write(new EntryId(name.substring(0, dot), name.substring(dot + 1)), code);
            });
        }
        return libraries;
    }
}
