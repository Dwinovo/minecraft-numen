package com.dwinovo.numen.api.script;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.agent.scope.Scope;
import com.dwinovo.numen.script.ScriptEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 随模组发布的 Lua 模块:Numen 与插件经 {@code NumenApi.bundleContent} 交来的 {@code numen/} 目录里的 {@code modules/<名字空间>/<组名><扩展名>}
 * (扩展名随脚本语言,{@link ScriptEngine#extension}),模块名是名字空间加组名({@code numen.work})。模块返回一张函数表,程序里
 * 以模块名直接用({@code numen.work.collect()});和第 ① 层的组同名的模块给那一组加函数。
 *
 * <p>它是 jar 里发的东西在进程里的一份登记:登记那一刻拦会坏事的,并给写法检查({@code ApiTester})与不带磁盘的文档读("只有出厂那一套"
 * 的 {@link Modules#factory})用。程序真正读的是服务器目录里同步出来的那一份,不是这里。
 *
 * <p>登记那一刻只拦会坏事的:名字合模块名的规矩(规矩只在 {@link Modules} 一处)、正文读得通(和运行时同一个编译器)、名字谁先
 * 登记归谁。不过当场抛出,模组起不来。开头那行说明、每个函数上面的注释是写法,缺了交给 {@code ApiTester} 的 lint 报告。
 *
 * <p>两侧都登记:大脑在服务端跑,公共代码在每个进程里各跑一遍。
 */
public final class BuiltinModules {

    /**
     * 一个内置模块。
     *
     * @param code    正文
     * @param summary 一句话说明(正文开头那行注释);没写是 null
     */
    public record Builtin(String code, String summary) {}

    private static final SortedMap<String, Builtin> MODULES = new TreeMap<>();

    private BuiltinModules() {}

    /**
     * 登记一个 {@code modules/} 目录里的全部模块:每个 {@code <名字空间>/<组名><扩展名>} 是名字空间下的一个。
     *
     * @return 撤回这一批的句柄
     * @throws IllegalArgumentException 见 {@link #register}
     */
    public static synchronized Handle bundle(Path root) {
        String ext = ScriptEngine.IN_USE.extension();
        Scope registered = new Scope();
        try (Stream<Path> spaces = Files.list(root)) {
            for (Path space : spaces.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> files = Files.list(space)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith(ext)).sorted().toList()) {
                        String group = file.getFileName().toString();
                        group = group.substring(0, group.length() - ext.length());
                        registered.add(register(space.getFileName() + "." + group,
                                Files.readString(file, StandardCharsets.UTF_8)));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 一批里有一个登记不了:已经登记的这几个一起撤回,不留半批
            registered.close();
            if (e instanceof IOException io) {
                throw new UncheckedIOException("读不了目录 " + root, io);
            }
            throw (RuntimeException) e;
        }
        return registered;
    }

    /**
     * 登记一个。
     *
     * @return 撤回它的句柄
     * @throws IllegalArgumentException 名字不合规矩、已有同名的、正文读不通
     */
    public static synchronized Handle register(String name, String code) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        String what = "内置模块 " + name;
        String badName = Modules.problem(name);
        if (badName != null) {
            throw new IllegalArgumentException(what + " 的名字不行: " + badName);
        }
        if (MODULES.containsKey(name)) {
            throw new IllegalArgumentException(what + " 登记了两次——名字谁先登记归谁");
        }
        String problem = engine.check(name, code);
        if (problem != null) {
            throw new IllegalArgumentException(what + " 读不通: " + problem);
        }
        Builtin made = new Builtin(code, engine.summary(code));
        MODULES.put(name, made);
        return Handle.once(() -> {
            synchronized (BuiltinModules.class) {
                if (MODULES.get(name) == made) {
                    MODULES.remove(name);
                }
            }
        });
    }

    /** 叫这个名字的内置模块;没有是 null。 */
    public static synchronized Builtin get(String name) {
        return MODULES.get(name);
    }

    /** 全部内置模块,按名字排;不可变快照。 */
    public static synchronized SortedMap<String, Builtin> all() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(MODULES));
    }
}
