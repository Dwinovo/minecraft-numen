package com.dwinovo.numen.script;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.cli.Names;

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
 * 随模组发布的脚本:core 与插件经 {@code NumenApi.bundleScripts} 交来一个目录,里面每个 {@code <名字><扩展名>} 是一份(扩展名
 * 随脚本语言,{@link ScriptEngine#extension})。只读——同名的
 * 存不进来,想改就另存一份。
 *
 * <p>登记那一刻就把关,和命令登记同一种做法:名字合规矩、正文读得通(和运行时同一个编译器)、开头一行注释说它做什么;任何一条
 * 不过当场抛出,模组起不来,不会带着坏脚本发出去。名字谁先登记归谁,撞了也当场抛出。
 *
 * <p>两侧都登记:大脑在主人客户端(评测时在服务端)跑脚本,存取与清单在服务端。
 */
public final class BuiltinScripts {

    /**
     * 一份内置脚本。
     *
     * @param code    正文
     * @param summary 一句话说明(正文开头那行注释)
     */
    public record Builtin(String code, String summary) {}

    private static final SortedMap<String, Builtin> SCRIPTS = new TreeMap<>();

    private BuiltinScripts() {}

    /**
     * 登记一个目录里的全部脚本。
     *
     * @throws IllegalArgumentException 名字不合规矩、已有同名的、正文读不通或开头没写说明
     */
    public static synchronized void bundle(Path root) {
        List<Path> files;
        try (Stream<Path> list = Files.list(root)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(ScriptEngine.IN_USE.extension())).sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读不了脚本目录 " + root, e);
        }
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - ScriptEngine.IN_USE.extension().length());
            try {
                register(name, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("读不了脚本 " + file, e);
            }
        }
    }

    /**
     * 登记一份。
     *
     * @throws IllegalArgumentException 名字不合规矩、已有同名的、正文读不通或开头没写说明
     */
    public static synchronized void register(String name, String code) {
        Names.checked("script", name);
        if (SCRIPTS.containsKey(name)) {
            throw new IllegalArgumentException("内置脚本 " + name + " 登记了两次——名字谁先登记归谁");
        }
        String problem = ScriptEngine.IN_USE.check(name, code);
        if (problem != null) {
            throw new IllegalArgumentException("内置脚本 " + name + " 读不通: " + problem);
        }
        String summary = ScriptEngine.IN_USE.summary(code);
        if (summary == null) {
            throw new IllegalArgumentException("内置脚本 " + name + " 开头没写一行注释说它做什么("
                    + ScriptEngine.IN_USE.comment("...") + ")");
        }
        SCRIPTS.put(name, new Builtin(code, summary));
    }

    /** 叫这个名字的内置脚本;没有是 null。 */
    public static synchronized Builtin get(String name) {
        return SCRIPTS.get(name);
    }

    /** 全部内置脚本,按名字排;不可变快照。 */
    public static synchronized SortedMap<String, Builtin> all() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(SCRIPTS));
    }
}
