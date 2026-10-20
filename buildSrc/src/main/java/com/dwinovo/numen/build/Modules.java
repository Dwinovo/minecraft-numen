package com.dwinovo.numen.build;

import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 纯 JVM 模块的清单——全构建里唯一的一份。Numen API 的发行 jar(各加载器的、Paper 的)把它们的类平铺进来,
 * 编译期的依赖、开发期运行的模组源码集、源码 jar 也都按这张表走,加一个模块只改这里。
 */
public final class Modules {

    /** 服务端也要的:落盘、LLM 连接、程序、大脑、权限规则、沙箱 Lua。 */
    public static final List<String> SERVER = List.of(":store", ":ai", ":script", ":agent", ":permission", ":lua", ":mcp");

    /** 只给客户端的:界面组件库。Paper 的服务端不带。 */
    public static final List<String> CLIENT = List.of(":ui");

    /** 平铺进模组加载器的发行 jar 的全部。 */
    public static final List<String> ALL = all();

    /** 插件(Numen、联动、第三方)经 POM 传递依赖拿到的:Numen API 公开签名里出现的类型所在的模块。 */
    public static final List<String> PLUGIN_VISIBLE = List.of(":ai", ":script", ":agent", ":permission", ":ui");

    /** 游戏(Minecraft、Paper)自带的库:嵌套进发行 jar 会和它们撞,所以不带。 */
    private static final Set<String> PROVIDED_GROUPS = Set.of("org.slf4j", "com.google.code.gson");

    private Modules() {}

    /**
     * mcp 模块带进来的第三方库(官方 MCP SDK、Jackson、Reactor、内嵌 Tomcat、Nimbus……)的坐标 {@code 组:名:版本},按解析结果,
     * 去掉游戏自带的。它们不是平铺进来的类:Fabric 用 include、NeoForge 用 jarJar 嵌套带上,Paper 把类平铺进插件 jar。
     * 清单从 mcp 的依赖解析出来,不另抄一份。
     */
    public static List<String> nestedLibraries(Project mcp) {
        return mcp.getConfigurations().getByName("runtimeClasspath").getResolvedConfiguration().getResolvedArtifacts().stream()
                .filter(a -> a.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier)
                .map(a -> (ModuleComponentIdentifier) a.getId().getComponentIdentifier())
                .filter(id -> !PROVIDED_GROUPS.contains(id.getGroup()))
                .map(id -> id.getGroup() + ":" + id.getModule() + ":" + id.getVersion())
                .distinct().sorted().collect(Collectors.toList());
    }

    private static List<String> all() {
        List<String> all = new ArrayList<>(SERVER);
        all.addAll(CLIENT);
        return List.copyOf(all);
    }
}
