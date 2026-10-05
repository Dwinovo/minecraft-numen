package com.dwinovo.numen.plugins;

import com.dwinovo.numen.core.Constants;

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
 * 只有瘦 api jar,引擎内部类够不着(见 buildSrc 的 numen-plugin.gradle)。
 *
 * <h2>分工</h2>
 * 目标模组在不在、jar 里的一条路径对应哪个 {@link Path},只有加载器答得上,两样都由
 * 构造时注入;本类是三个加载器共用的那部分——判、装、定位技能。清单在各加载器模块的
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
     *                  各加载器模块的 {@code ModJar.find}——core 自己的 skills 根也从那里取
     */
    public Gate(Predicate<String> modLoaded, Function<String, Path> inJar) {
        this.modLoaded = modLoaded;
        this.inJar = inJar;
    }

    /**
     * 带技能的联动。
     *
     * @param modId   目标模组;不在就整块跳过
     * @param plugin  联动的模块名({@code plugins/} 下的目录名),用来定位它自带的技能
     * @param body    延迟到判据为真之后才求值——理由见类注释
     */
    public void open(String modId, String plugin, Function<Path, Runnable> body) {
        if (!modLoaded.test(modId)) return;
        install(modId, () -> body.apply(skillsRoot(plugin)).run());
    }

    /**
     * 不带技能的联动(它补的是已有的能力,用法由那个能力自己的描述讲清,比如穿戴来源):不去找技能目录,
     * 也就不会为"没有技能"报警。
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
     * 一个带技能的联动自带的技能根:{@code plugins/<模块名>/skills/};jar 里没有就 null,联动照装、
     * 只是不带技能——留一条 warn,说好要带的不能悄悄少了。
     *
     * <p>目录就叫 {@code skills},但必须挂在 {@code plugins/<模块名>/} 底下——jar 是平的,
     * 源码树里 {@code plugins/ysm/} 那层前缀打包时就没了。直接放 {@code skills/} 的话会和
     * core 自己那份合并,而 core 声明的是<b>整个根</b>、无条件:没装 YSM 的玩家提示词里也会
     * 出现"怎么换 YSM 模型",纯噪音,而且照做也没用。加一层命名空间是每个模组都在做的事
     * ({@code assets/<modid>/…} 同理)。
     *
     * <p>给的是整个 {@code skills/} 根而不是某一篇,所以一个联动想带几篇就带几篇,
     * 不用回来改这里。
     */
    private Path skillsRoot(String plugin) {
        String path = "plugins/" + plugin + "/skills";
        Path root = inJar.apply(path);
        if (root == null) {
            Constants.LOG.warn("[numen] 联动 {} 的技能目录 {} 不在 jar 里,工具照常、技能不带", plugin, path);
        }
        return root;
    }
}
