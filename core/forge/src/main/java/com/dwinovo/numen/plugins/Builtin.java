package com.dwinovo.numen.plugins;

import com.dwinovo.numen.Constants;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 内嵌的联动模组:<b>装了目标模组就装上,没装就当不存在</b>。
 *
 * <h2>它们是什么</h2>
 * {@code plugins/} 下每一个都是独立的联动模组,只是被内嵌进这个 jar 一起发,省得玩家
 * 为了让同伴有张脸再去装第三个文件。登记方式和第三方插件<b>一字不差</b>——全部经
 * {@code NumenPlugins.register} 那扇门;编译期看得见的东西也一样,它们的类路径上
 * 只有瘦 api jar,引擎内部类够不着(见 buildSrc 的 numen-plugin.gradle)。
 *
 * <h2>为什么要多套一层 Supplier</h2>
 * 直接传 {@code Runnable} 的话,{@code NumenTlm::install} 这个方法引用在<b>创建
 * lambda 那一刻</b>就要解析方法句柄,{@code NumenTlm} 当场被类加载——而它直接引用
 * 车万女仆的类,那个模组不在就是 {@code NoClassDefFoundError},闸门形同虚设。
 * 套一层之后,判据为假就永远走不到内层,那个类一次都不会被加载。
 *
 * <p>这个写法是社区惯例(Create 的 {@code Mods.executeIfInstalled} 是同一形状)。
 *
 * <h2>清单在这里,不做扫描</h2>
 * 内嵌联动是<b>闭合集合</b>,数量由我们自己定;扫描是给开放集合用的。列在一处,
 * "现在内嵌了哪些、各自要谁"一眼答得完;换成扫描就只能靠 grep 了。
 */
public final class Builtin {

    private Builtin() {}

    public static void registerAll(IEventBus modBus) {
        gate("yes_steve_model", "ysm",
                skills -> () -> com.dwinovo.numen.plugins.ysm.NumenYsm.install(skills));
        gate("touhou_little_maid", "tlm",
                skills -> () -> com.dwinovo.numen.plugins.tlm.NumenTlm.install(modBus, skills));
        // 注:这个 MC 版本上车万女仆没有按坐标播语音的口,所以那个联动只做模型不做语音。
    }

    /**
     * @param modId   目标模组;不在就整块跳过
     * @param plugin  联动的模块名({@code plugins/} 下的目录名),用来定位它自带的技能
     * @param body    延迟到判据为真之后才求值——理由见类注释
     */
    private static void gate(String modId, String plugin, java.util.function.Function<Path, Runnable> body) {
        if (!ModList.get().isLoaded(modId)) return;
        try {
            body.apply(skillsRoot(plugin)).run();
            Constants.LOG.info("[numen] 联动已接上:{}", modId);
        } catch (Throwable t) {
            // 一个联动接不上不能带倒整个模组,也不能带倒别的联动
            Constants.LOG.warn("[numen] 联动 {} 没接上,其余照常:{}", modId, t.toString());
        }
    }

    /**
     * 一个联动自带的技能根:{@code plugins/<模块名>/skills/}。
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
    private static Path skillsRoot(String plugin) {
        // 经类加载器取,不用 NeoForge 的 IModFile —— 后者的口跨 MC 版本一直在变
        // (26.x 上 getModFileById(...).getFile() 就没了),而资源 URL 在哪个版本都成立。
        // core 自己声明 skills/ 用的也是这条路,两处同一个写法。
        try {
            java.net.URL url = Builtin.class.getResource("/plugins/" + plugin + "/skills");
            return url == null ? null : Path.of(url.toURI());
        } catch (Exception ignored) {
            return null;   // 找不到就不带技能,工具照常能用
        }
    }
}
