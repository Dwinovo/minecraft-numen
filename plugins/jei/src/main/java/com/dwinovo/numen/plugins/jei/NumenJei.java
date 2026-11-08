package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.knowledge.KnowledgeKind;

import java.util.List;

/**
 * JEI 联动:主人客户端上的 JEI 是配方与物品知识的一个来源({@code numen.inv.recipes}、{@code uses}、{@code info} 的动词把它的答复合进结果)。
 *
 * <p>JEI 只有客户端有运行时,所以这是<b>目标模组只在客户端</b>的联动(同 Litematica):登记在两侧无条件做,"有没有"由主人的客户端自己问平台、报给服务端;
 * 来源的函数全是客户端函数,经现有的客户端调用通道问,在客户端主线程上执行。这个类和三个来源的签名里没有 JEI 的类型,碰 JEI 的是
 * {@link JeiLookups} 与 {@link NumenJeiPlugin},JEI 不在的客户端与专用服务器上它们不会被加载。
 */
public final class NumenJei {

    /** 这个联动在她的 API 里的名字空间,也是它在结果里的来源名。 */
    static final String NAMESPACE = "jei";

    /** JEI 的模组 id。 */
    static final String TARGET_MOD = "jei";

    /** JEI 在配方的界面上画、而不留成结构化数据的东西:不读,也就不给,更不从提示文字里抠。 */
    private static final List<String> NOT_PROVIDED = List.of("chance", "time", "heat", "energy",
            "which inputs are alternatives of one slot");

    private static volatile JeiHost host;

    private NumenJei() {}

    /** 宿主:加载器各不相同的那件事(流体数量)的答案。 */
    static JeiHost host() {
        return host;
    }

    /** 由 {@code Gate#openClientMod} 放行后调用,不问 JEI 在不在。 */
    public static void install(JeiHost loader) {
        host = loader;
        NumenPlugins.registerForClientMod(NAMESPACE, TARGET_MOD, numen -> {
            numen.knowledgeSource(KnowledgeKind.RECIPES, "which machine does it, the category's name in your owner's "
                    + "language, fluids with their amounts", NOT_PROVIDED, JeiRecipes.class);
            numen.knowledgeSource(KnowledgeKind.USES, "which machine does it, the category's name in your owner's "
                    + "language, fluids with their amounts", NOT_PROVIDED, JeiUses.class);
            numen.knowledgeSource(KnowledgeKind.INFO, "the description pages the mod or the modpack wrote for the item",
                    List.of("facts"), JeiInfo.class);
        });
    }
}
