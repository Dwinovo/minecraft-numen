package com.dwinovo.numen.script;

import com.dwinovo.numen.agent.script.ScriptEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 随模组发布的 Lua 模块:core 与插件经 {@code NumenApi.bundleModules} 交来一个目录,里面每个 {@code <名字><扩展名>} 是一个模块
 * (扩展名随脚本语言,{@link ScriptEngine#extension})。模块返回一张函数表,程序里以模块名直接用({@code work.collect()});和第 ① 层的
 * 组同名的模块给那一组加函数。它们留在 jar 里,是她那一层的底:她存一份同名的就盖住它({@link Modules})。
 *
 * <p>登记那一刻把关,和动作登记同一种做法:名字合模块名的规矩({@link ScriptEngine#moduleName},规则只在那一处)、正文读得通(和运行时
 * 同一个编译器)、开头一行注释说它做什么、至少定义一个函数而且每个函数上面都写了注释(帮助与索引里它的说明就是那几行)。返回的是不是
 * 一张表、有没有给第 ① 层的名字赋值,要等各组到齐才查得全,在登记处第一次被用时查({@code NumenCli})。任何一条不过当场抛出,模组起
 * 不来,不会带着坏模块发出去。名字谁先登记归谁,撞了也当场抛出。
 *
 * <p>两侧都登记:大脑在主人客户端(评测与 GameTest 在服务端)跑程序,公共代码在每个进程里各跑一遍。
 */
public final class BuiltinModules {

    /**
     * 一个内置模块。
     *
     * @param code    正文
     * @param summary 一句话说明(正文开头那行注释)
     */
    public record Builtin(String code, String summary) {}

    private static final SortedMap<String, Builtin> MODULES = new TreeMap<>();

    private BuiltinModules() {}

    /**
     * 登记一个目录里的全部模块。
     *
     * @throws IllegalArgumentException 见 {@link #register}
     */
    public static synchronized void bundle(Path root) {
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
                register(name, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("读不了 " + file, e);
            }
        }
    }

    /**
     * 登记一个。
     *
     * @throws IllegalArgumentException 名字不合规矩、已有同名的、正文读不通、开头没写说明、一个函数都没定义,或有函数没写注释
     */
    public static synchronized void register(String name, String code) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        String what = "内置模块 " + name;
        String badName = engine.moduleName(name);
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
        String summary = engine.summary(code);
        if (summary == null) {
            throw new IllegalArgumentException(what + " 开头没写一行注释说它做什么(" + engine.comment("...") + ")");
        }
        List<ScriptEngine.Defined> functions = engine.functions(name, code);
        if (functions.isEmpty()) {
            throw new IllegalArgumentException(what + " 一个函数都没定义");
        }
        for (ScriptEngine.Defined fn : functions) {
            if (engine.summaryOf(fn).isEmpty()) {
                throw new IllegalArgumentException(what + " 的函数 " + fn.name() + " 上面没写注释——帮助与索引里它的说明"
                        + "就是这几行");
            }
        }
        MODULES.put(name, new Builtin(code, summary));
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
