package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.sdk.Binder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一个问题:出题的一方定下"我要问什么"({@code T} 是回答者的样子),谁懂谁经 {@link NumenApi#answer} 登记一个回答者,出题的一方要用时
 * 拿着这个常量问一圈({@link #answerers})。各家模组没有共同的父类,"认出它是哪个类再解析"答不了的事都这样问(control 0020)。
 *
 * <h2>为什么是常量而不是一个问题一个登记方法</h2>
 * 一个问题一个方法的话,每加一个问题就得改一次门的形状;而这里加问题只是多一个常量,{@code answer(…)} 一个字不用动(同
 * {@link CompanionEvent})。插件自己也能出题:在自己的类里放一个常量,别的插件拿它登记回答者。
 *
 * <h2>回答者有名字</h2>
 * 每个回答者登记时带一个名字,一个问题里不重名:它说的话归在这个名字下(脚本里是一个字段名,所以写法同函数名、选项名),出错时日志点的
 * 也是它。
 *
 * <p>订阅用的是常量本身,{@code id} 只用于日志与排错。回答者按登记先后排;出题的一方定这个先后算不算数(挨个都问,或第一个答得上的
 * 算数)。一个回答者出错不该连累别的回答者,也不该连累正在做的事:出题的一方问的时候逐个隔离。
 *
 * @param <T> 回答者的类型
 */
public final class Question<T> {

    private final String id;
    /** 名字到回答者,按登记先后;读写都锁它。 */
    private final Map<String, T> answerers = new LinkedHashMap<>();

    private Question(String id) {
        this.id = id;
    }

    /**
     * 出一道题。放进一个 {@code static final} 常量里,登记与提问都用它。
     *
     * @param id 写进日志的名字;插件出的题带上自己的名字空间({@code mymod:item_namer})
     */
    public static <T> Question<T> of(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a question needs an id");
        }
        return new Question<>(id);
    }

    public String id() {
        return id;
    }

    /** 此刻登记着的回答者:名字到回答者,按登记先后。 */
    public Map<String, T> answerers() {
        synchronized (answerers) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(answerers));
        }
    }

    /**
     * 登记一个回答者,交回只撤这一条的句柄。<b>Numen API 内部调用</b>,插件经 {@link NumenApi#answer}(那样登记才归在插件名下、
     * 随插件撤回)。
     *
     * @param name 回答者的名字:小写字母开头,小写字母、数字、下划线
     * @throws IllegalArgumentException 名字在脚本里写不出来,或这个问题已经有一个同名的回答者
     */
    public Handle register(String name, T answerer) {
        Objects.requireNonNull(answerer, "answerer");
        String bad = Binder.badName(name);
        if (bad != null) {
            throw new IllegalArgumentException("question " + id + ": the name of an answerer: " + bad);
        }
        synchronized (answerers) {
            if (answerers.putIfAbsent(name, answerer) != null) {
                throw new IllegalArgumentException("question " + id + " already has an answerer named '" + name + "'");
            }
        }
        return Handle.once(() -> {
            synchronized (answerers) {
                // 只撤自己登记的那一个:这个名字撤掉之后别人可以再登记
                if (answerers.get(name) == answerer) {
                    answerers.remove(name);
                }
            }
        });
    }

    @Override
    public String toString() {
        return id;
    }
}
