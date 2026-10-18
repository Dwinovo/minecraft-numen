package com.dwinovo.numen.build;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 JVM 模块的清单——全构建里唯一的一份。Numen API 的发行 jar(各加载器的、Paper 的)把它们的类平铺进来,
 * 编译期的依赖、开发期运行的模组源码集、源码 jar 也都按这张表走,加一个模块只改这里。
 */
public final class Modules {

    /** 服务端也要的:落盘、LLM 连接、程序、大脑、权限规则、沙箱 Lua。 */
    public static final List<String> SERVER = List.of(":store", ":ai", ":script", ":agent", ":permission", ":lua");

    /** 只给客户端的:界面组件库。Paper 的服务端不带。 */
    public static final List<String> CLIENT = List.of(":ui");

    /** 平铺进模组加载器的发行 jar 的全部。 */
    public static final List<String> ALL = all();

    /** 插件(Numen、联动、第三方)经 POM 传递依赖拿到的:Numen API 公开签名里出现的类型所在的模块。 */
    public static final List<String> PLUGIN_VISIBLE = List.of(":ai", ":script", ":agent", ":permission", ":ui");

    private Modules() {}

    private static List<String> all() {
        List<String> all = new ArrayList<>(SERVER);
        all.addAll(CLIENT);
        return List.copyOf(all);
    }
}
