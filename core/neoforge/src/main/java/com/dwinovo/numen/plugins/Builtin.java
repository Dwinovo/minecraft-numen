package com.dwinovo.numen.plugins;

import com.dwinovo.numen.Constants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

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
        gate("yes_steve_model", "ysm_look",
                skills -> () -> com.dwinovo.numen.plugins.ysm.NumenYsm.install(skills));
        gate("touhou_little_maid", "maid_look",
                skills -> () -> com.dwinovo.numen.plugins.tlm.NumenTlm.install(modBus, skills));
    }

    /**
     * @param modId  目标模组;不在就整块跳过
     * @param skill  这个联动自带的技能目录名(在 jar 的 {@code plugin_skills/} 下)
     * @param body   延迟到判据为真之后才求值——理由见类注释
     */
    private static void gate(String modId, String skill, java.util.function.Function<Path, Runnable> body) {
        if (!ModList.get().isLoaded(modId)) return;
        try {
            body.apply(skillsRoot(skill)).run();
            Constants.LOG.info("[numen] 联动已接上:{}", modId);
        } catch (Throwable t) {
            // 一个联动接不上不能带倒整个模组,也不能带倒别的联动
            Constants.LOG.warn("[numen] 联动 {} 没接上,其余照常:{}", modId, t.toString());
        }
    }

    /**
     * 联动的技能放在 {@code plugin_skills/<名字>/} 而不是 {@code skills/}。
     * 后者是引擎无条件加载的那一份——技能混进去的话,没装 YSM 的玩家也会在提示词里
     * 看到"怎么换 YSM 模型",那是纯粹的噪音,而且他们照做也没用。
     */
    private static Path skillsRoot(String name) {
        try {
            return ModList.get().getModFileById(Constants.MOD_ID)
                    .getFile().findResource("plugin_skills", name);
        } catch (Throwable ignored) {
            return null;   // 找不到就不带技能,工具照常能用
        }
    }
}
