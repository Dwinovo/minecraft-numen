package com.dwinovo.numen.api.knowledge;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.sdk.ApiFunction;
import com.dwinovo.numen.api.sdk.ApiRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 三个知识动词({@link KnowledgeKind})的来源表。一个来源是一个插件(或 Numen 自己)登记的一个隐藏的 API 函数:它在哪一端执行由函数自己
 * 的描述说({@link ApiFunction#side}),在主人客户端上的就经现有的客户端调用通道问,不另开一条。动词在脚本里读这张表
 * ({@code numen.api.sources}),逐个调来源、合并结果;哪个来源没答上、为什么,在调用那一刻由调用的失败如实给出,这里不预判。
 *
 * <p>登记经插件的门({@link com.dwinovo.numen.api.NumenApi#knowledgeSource}),撤回时只撤自己那一条(句柄按身份比)。
 * 线程:任何线程。
 */
public final class KnowledgeSources {

    /**
     * 一个来源。
     *
     * @param id          来源的名字:登记它的名字空间(插件 id,Numen 自己的是 {@code numen})
     * @param call        脚本里调它的全名,{@code jei.recipes_source.read}
     * @param side        在哪一端执行
     * @param provides    它多给的是哪一类信息,来源缺席时据此对模型说缺了什么
     * @param notProvided 它读不到、因此不会给的字段(概率、耗时、热量、能量……),不猜
     */
    public record Source(String id, KnowledgeKind kind, String call, ApiFunction.Side side, String provides,
                         List<String> notProvided) {}

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private KnowledgeSources() {}

    /**
     * 登记一个来源:{@code function} 里恰有一个 {@code @Fn} 静态方法,收一个物品({@code Item} 参数 record),交回 {@link KnowledgeKind#returns}
     * 那种页。它登记进 {@code namespace} 的隐藏组 {@link KnowledgeKind#group}。
     *
     * @return 撤回这一条的句柄
     * @throws IllegalArgumentException 这个名字空间的这一种来源已经有了、函数写得不对
     */
    public static Handle register(String namespace, KnowledgeKind kind, String provides, List<String> notProvided,
                                  Class<?> function) {
        return listed(namespace, kind, provides, notProvided,
                ApiRegistry.registerHidden(namespace, kind.group(), summary(kind), function), function.getSimpleName());
    }

    /** 同上,函数的描述用代码写({@link ApiFunction#builder},脚本里写的来源走这条)。 */
    public static Handle register(String namespace, KnowledgeKind kind, String provides, List<String> notProvided,
                                  ApiFunction function) {
        return listed(namespace, kind, provides, notProvided,
                ApiRegistry.registerHidden(namespace, kind.group(), summary(kind), List.of(function)), function.name());
    }

    private static String summary(KnowledgeKind kind) {
        return "Knowledge source of " + kind.verb() + ".";
    }

    /** 隐藏组登记好了:它的那一个函数就是来源;不是恰好一个就把组撤回。 */
    private static Handle listed(String namespace, KnowledgeKind kind, String provides, List<String> notProvided,
                                 Handle group, String what) {
        List<ApiFunction> functions = ApiRegistry.group(namespace + "." + kind.group()).functions();
        if (functions.size() != 1) {
            group.close();
            throw new IllegalArgumentException(namespace + ": a " + kind.verb() + " source is one function, " + what
                    + " has " + functions.size());
        }
        ApiFunction fn = functions.get(0);
        Source source = new Source(namespace, kind, fn.fullName(), fn.side(), provides, List.copyOf(notProvided));
        SOURCES.add(source);
        return Handle.once(() -> {
            SOURCES.remove(source);
            group.close();
        });
    }

    /** 这个动词现有的来源,按登记顺序。 */
    public static List<Source> of(KnowledgeKind kind) {
        return SOURCES.stream().filter(s -> s.kind() == kind).toList();
    }
}
