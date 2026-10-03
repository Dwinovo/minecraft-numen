package com.dwinovo.numen.script;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.cli.Names;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 随模组发布的脚本与库:core 与插件经 {@code NumenApi.bundleScripts}、{@code NumenApi.bundleLibrary} 交来一个目录,里面每个
 * {@code <名字><扩展名>} 是一份(扩展名随脚本语言,{@link ScriptEngine#extension})。只读——同名的存不进来,想改就另存一份。
 *
 * <ul>
 *   <li><b>脚本</b>:按名字跑({@code script.run("mine", "ores")})。</li>
 *   <li><b>库</b>:每段脚本开跑之前先跑,它定义的函数脚本里直接能调(多半写进某一组的表里:{@code move.goto_})。系统提示的 API 索引
 *       列出每个库函数,说明是紧挨在定义上面的那几行注释。</li>
 * </ul>
 * 两种都能 {@code script.show} 读全文,名字在同一个名字空间里。
 *
 * <p>登记那一刻就把关,和动作登记同一种做法:名字合规矩、正文读得通(和运行时同一个编译器)、开头一行注释说它做什么;库还要每个顶层
 * 函数上面都写了注释。任何一条不过当场抛出,模组起不来,不会带着坏脚本发出去。名字谁先登记归谁,撞了也当场抛出。
 *
 * <p>两侧都登记:大脑在主人客户端(评测时在服务端)跑脚本,存取与清单在服务端。
 */
public final class BuiltinScripts {

    /**
     * 一份内置脚本或库。
     *
     * @param code    正文
     * @param summary 一句话说明(正文开头那行注释)
     * @param library 是库(每段脚本先跑它),不是按名字跑的脚本
     */
    public record Builtin(String code, String summary, boolean library) {}

    private static final SortedMap<String, Builtin> SCRIPTS = new TreeMap<>();
    /** 库按登记的先后跑:后登记的库可以用先登记的库里的函数。 */
    private static final Map<String, Builtin> LIBRARIES = new LinkedHashMap<>();

    private BuiltinScripts() {}

    /**
     * 登记一个目录里的全部脚本,或全部库。
     *
     * @throws IllegalArgumentException 名字不合规矩、已有同名的、正文读不通、开头没写说明,或库里有函数没写注释
     */
    public static synchronized void bundle(Path root, boolean library) {
        List<Path> files;
        try (Stream<Path> list = Files.list(root)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(ScriptEngine.IN_USE.extension())).sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读不了目录 " + root, e);
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - ScriptEngine.IN_USE.extension().length());
            try {
                register(name, Files.readString(file, StandardCharsets.UTF_8), library);
            } catch (IOException e) {
                throw new UncheckedIOException("读不了 " + file, e);
            }
        }
    }

    /**
     * 登记一份。
     *
     * @throws IllegalArgumentException 名字不合规矩、已有同名的、正文读不通、开头没写说明,或库里有函数没写注释
     */
    public static synchronized void register(String name, String code, boolean library) {
        String what = library ? "内置库 " : "内置脚本 ";
        Names.checked("script", name);
        if (SCRIPTS.containsKey(name)) {
            throw new IllegalArgumentException(what + name + " 登记了两次——名字谁先登记归谁");
        }
        String problem = ScriptEngine.IN_USE.check(name, code);
        if (problem != null) {
            throw new IllegalArgumentException(what + name + " 读不通: " + problem);
        }
        String summary = ScriptEngine.IN_USE.summary(code);
        if (summary == null) {
            throw new IllegalArgumentException(what + name + " 开头没写一行注释说它做什么("
                    + ScriptEngine.IN_USE.comment("...") + ")");
        }
        if (library) {
            List<ScriptEngine.Defined> functions = ScriptEngine.IN_USE.functions(code);
            if (functions.isEmpty()) {
                throw new IllegalArgumentException(what + name + " 一个函数都没定义");
            }
            for (ScriptEngine.Defined fn : functions) {
                if (fn.doc().isBlank()) {
                    throw new IllegalArgumentException(what + name + " 的函数 " + fn.name() + " 上面没写注释——API 索引"
                            + "里它的说明就是这几行");
                }
            }
        }
        Builtin builtin = new Builtin(code, summary, library);
        SCRIPTS.put(name, builtin);
        if (library) {
            LIBRARIES.put(name, builtin);
        }
    }

    /** 叫这个名字的内置脚本或库;没有是 null。 */
    public static synchronized Builtin get(String name) {
        return SCRIPTS.get(name);
    }

    /** 全部内置脚本与库,按名字排;不可变快照。 */
    public static synchronized SortedMap<String, Builtin> all() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(SCRIPTS));
    }

    /** 全部库,按登记的先后;不可变快照。 */
    public static synchronized Map<String, Builtin> libraries() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(LIBRARIES));
    }
}
