package com.dwinovo.numen.plugins;

import com.dwinovo.numen.Constants;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 内嵌联动的闸门:<b>目标模组在场才装,不在就当不存在</b>。
 *
 * <h2>内嵌联动是什么</h2>
 * {@code plugins/} 下每一个都是独立的联动模组,只是被内嵌进这个 jar 一起发,省得玩家
 * 为了让同伴有张脸再去装第三个文件。登记方式和第三方插件<b>一字不差</b>——全部经
 * {@code NumenPlugins.register} 那扇门;编译期看得见的东西也一样,它们的类路径上
 * 只有瘦 api jar,Numen API 内部类够不着(见 buildSrc 的 numen-plugin.gradle)。
 *
 * <h2>分工</h2>
 * 目标模组在不在、jar 里的一条路径对应哪个 {@link Path},只有加载器答得上,两样都由
 * 构造时注入;本类是三个加载器共用的那部分——判、装、定位随联动发的内容。清单在各加载器模块的
 * {@code Builtin} 里,它同时替联动做那几件加载器各不相同的事。
 *
 * <h2>为什么要多套一层</h2>
 * 直接传 {@code Runnable} 的话,{@code NumenTlm::install} 这个方法引用在<b>创建
 * lambda 那一刻</b>就要解析方法句柄,{@code NumenTlm} 当场被类加载——而它直接引用
 * 车万女仆的类,那个模组不在就是 {@code NoClassDefFoundError},闸门形同虚设。
 * 套一层之后,判据为假就永远走不到内层,那个类一次都不会被加载。
 *
 * <p>这个写法是社区惯例(Create 的 {@code Mods.executeIfInstalled} 是同一形状)。
 */
public final class Gate {

    private final Predicate<String> modLoaded;
    private final Function<String, Path> inJar;

    /**
     * @param modLoaded 问加载器:这个 mod id 装了没
     * @param inJar     问加载器:本模组 jar 里的这条路径对应哪个 {@link Path},没有这条路径给 null。
     *                  各加载器模块的 {@code ModJar.find}——Numen 自己的 numen/ 目录也从那里取
     */
    public Gate(Predicate<String> modLoaded, Function<String, Path> inJar) {
        this.modLoaded = modLoaded;
        this.inJar = inJar;
    }

    /**
     * 带内容(技能、Lua 模块)的联动。
     *
     * @param modId   目标模组;不在就整块跳过
     * @param plugin  联动的模块名({@code plugins/} 下的目录名),用来定位它自带的内容
     * @param body    延迟到判据为真之后才求值——理由见类注释
     */
    public void open(String modId, String plugin, Function<Path, Runnable> body) {
        if (!modLoaded.test(modId)) return;
        install(modId, () -> body.apply(contentRoot(plugin)).run());
    }

    /**
     * 不带内容的联动(它补的是已有的能力,用法由那个能力自己的描述讲清,比如穿戴来源):不去找内容目录,
     * 也就不会为"没有内容"报警。
     *
     * @param body 延迟到判据为真之后才求值——理由见类注释
     */
    public void open(String modId, Supplier<Runnable> body) {
        if (!modLoaded.test(modId)) return;
        install(modId, () -> body.get().run());
    }

    private void install(String modId, Runnable run) {
        try {
            run.run();
            Constants.LOG.info("[numen] 联动已接上:{}", modId);
        } catch (Throwable t) {
            // 一个联动接不上不能带倒整个模组,也不能带倒别的联动
            Constants.LOG.warn("[numen] 联动 {} 没接上,其余照常:{}", modId, t.toString());
        }
    }

    /**
     * 一个带内容的联动自带的 {@code numen/} 目录:{@code plugins/<模块名>/numen/},里面是 {@code <库>/<命名空间>/<名字>},
     * 和独立发布的插件 jar 里的 {@code numen/} 一个样;jar 里没有就 null,联动照装、只是不带内容——留一条 warn,
     * 说好要带的不能悄悄少了。
     *
     * <p>必须挂在 {@code plugins/<模块名>/} 底下——jar 是平的,源码树里 {@code plugins/ysm/} 那层前缀打包时就没了。
     * 直接放 {@code numen/} 的话会和 Numen 自己那份合并,而 Numen 的内容是无条件同步的:没装 YSM 的服务器上也会出现
     * "怎么换 YSM 模型",纯噪音,而且照做也没用。
     *
     * <p>给的是整个 {@code numen/} 目录而不是某一篇,所以一个联动想带什么就带什么,不用回来改这里。
     */
    private Path contentRoot(String plugin) {
        String path = "plugins/" + plugin + "/numen";
        Path root = inJar.apply(path);
        if (root == null) {
            Constants.LOG.warn("[numen] 联动 {} 的内容目录 {} 不在 jar 里,工具照常、内容不带", plugin, path);
        }
        return root;
    }
}
